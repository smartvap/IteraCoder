package com.agenthub.ai.datasource.service;

import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.datasource.adapter.DsConfigHealthAdapter;
import com.agenthub.ai.datasource.config.DsProperties;
import com.agenthub.ai.datasource.constant.AuditAction;
import com.agenthub.ai.datasource.constant.CheckType;
import com.agenthub.ai.datasource.constant.DsErrorCode;
import com.agenthub.ai.datasource.dto.DsTestConnectionRequest;
import com.agenthub.ai.datasource.entity.DsConfig;
import com.agenthub.ai.datasource.entity.DsConfigAdmin;
import com.agenthub.ai.datasource.entity.DsConfigHealth;
import com.agenthub.ai.datasource.mapper.DsConfigAdminMapper;
import com.agenthub.ai.datasource.mapper.DsConfigHealthMapper;
import com.agenthub.ai.datasource.mapper.DsConfigManageMapper;
import com.agenthub.ai.datasource.vo.AuditChange;
import com.agenthub.ai.datasource.vo.HealthCheckResultVO;
import com.agenthub.ai.dbaccess.constant.DbAccessConstants;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import com.agenthub.ai.dbaccess.registry.EnvPlaceholderResolver;
import com.agenthub.ai.dbaccess.validation.OceanBaseAccountValidator;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 数据源接入健康检查服务（FR-BE-004 / FR-002）。
 *
 * <p>testConnection：一次性注入 passwordForTest，不落库主表、不写审计/日志明文；
 * healthCheck：读取已登记配置（密码经 ${ENV} 解析）执行完整探测并留痕。passwordForTest 绝不落任何存储。</p>
 */
@Slf4j
@Service
public class DsHealthCheckService {

    private final DsConfigManageMapper configManageMapper;
    private final DsConfigAdminMapper adminMapper;
    private final DsConfigHealthMapper healthMapper;
    private final DsConfigHealthAdapter healthAdapter;
    private final EnvPlaceholderResolver placeholderResolver;
    private final DsAuditService auditService;
    private final DsProperties properties;

    public DsHealthCheckService(DsConfigManageMapper configManageMapper,
                                DsConfigAdminMapper adminMapper,
                                DsConfigHealthMapper healthMapper,
                                DsConfigHealthAdapter healthAdapter,
                                EnvPlaceholderResolver placeholderResolver,
                                DsAuditService auditService,
                                DsProperties properties) {
        this.configManageMapper = configManageMapper;
        this.adminMapper = adminMapper;
        this.healthMapper = healthMapper;
        this.healthAdapter = healthAdapter;
        this.placeholderResolver = placeholderResolver;
        this.auditService = auditService;
        this.properties = properties;
    }

    /**
     * 一次性测试连接（api-contract 8）：passwordForTest 仅请求内存，使用后即弃。
     */
    public HealthCheckResultVO testConnection(DsTestConnectionRequest req, Long operatorId) {
        validateTestRequest(req);
        int timeout = clampTimeout(req.getTimeoutMs());
        // 复用已存在数据源配置：白名单（请求未携带时）与可访问库集合（DsTestConnectionRequest 无该字段，
        // 仅有 dsId 时按已登记配置 space_names 补充；候选测试无 dsId 则不限制库集合，单库旧行为）
        com.agenthub.ai.dbaccess.model.WhitelistRule whitelist = req.getWhitelist();
        List<String> spaceNames = null;
        if (req.getDsId() != null && !req.getDsId().isBlank()) {
            DsConfig existing = configManageMapper.selectOne(new LambdaQueryWrapper<DsConfig>()
                    .eq(DsConfig::getDsId, req.getDsId())
                    .eq(DsConfig::getIsDeleted, 0));
            if (existing != null) {
                if (whitelist == null) {
                    whitelist = com.agenthub.ai.dbaccess.model.WhitelistRule.fromJson(existing.getWhitelistJson());
                }
                spaceNames = parseSpaceNames(existing.getSpaceNamesJson());
            }
        }

        DsConfigHealthAdapter.HealthProbeParam param = DsConfigHealthAdapter.HealthProbeParam.builder()
                .dsId(req.getDsId())
                .dbType(req.getDbType())
                .jdbcUrl(req.getJdbcUrl())
                .readonlyUser(req.getReadonlyUser())
                .password(resolveTestPassword(req.getPasswordForTest()))
                .whitelist(whitelist)
                .spaceNames(spaceNames)
                .timeoutMs(timeout)
                .build();
        HealthCheckResultVO vo = healthAdapter.probe(param);
        vo.setCheckType(CheckType.TEST_CONNECTION.name());
        recordHealth(req.getDsId(), CheckType.TEST_CONNECTION, vo, operatorId);
        // 审计留痕（脱敏，不含密码）：候选配置测试（ds_id 为空）时 t_ds_config_audit.ds_id 为 NOT NULL，
        // 跳过审计写入避免落库失败（仅 t_ds_config_health 留痕）；已登记数据源（ds_id 非空）才写 TEST_CONNECTION 审计
        if (req.getDsId() != null && !req.getDsId().isBlank()) {
            auditService.writeAudit(req.getDsId(), AuditAction.TEST_CONNECTION.name(), operatorId, null,
                    "测试连接结果：" + vo.getCheckResult(), null,
                    vo.getCheckResult() == null || "FAIL".equals(vo.getCheckResult()) ? "FAIL" : "SUCCESS",
                    vo.getErrorMessage());
        }
        return vo;
    }

    /**
     * 对已登记数据源执行健康检查并留痕（api-contract 9）。密码从 ${ENV} 引用解析，不接收明文。
     */
    public HealthCheckResultVO healthCheck(String dsId, Long operatorId) {
        HealthCheckResultVO vo = runHealthCheck(dsId, operatorId, true);
        return vo;
    }

    /**
     * 内部执行健康检查：持久化 health + 更新 admin 最近健康摘要。
     *
     * <p>本方法以 REQUIRES_NEW 独立事务提交：enable 前置健康检查调用本方法时，
     * 即使后续状态翻转失败（如 FAIL 抛 43005 回滚 enable 外层事务），
     * health 历史与 admin.last_health_* 摘要也已先行提交，保证失败诊断留痕不丢（spec A-2 验收 3/5）。</p>
     *
     * @param withAudit 是否额外写 HEALTH_CHECK 审计（enable 前置检查时由调用方统一审计，传 false）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public HealthCheckResultVO runHealthCheck(String dsId, Long operatorId, boolean withAudit) {
        DsConfig config = configManageMapper.selectOne(new LambdaQueryWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId)
                .eq(DsConfig::getIsDeleted, 0));
        if (config == null) {
            throw new BusinessException(DsErrorCode.DS_NOT_FOUND, "数据源不存在或已删除");
        }
        String password;
        try {
            password = placeholderResolver.resolvePasswordRef(config.getReadonlyPasswordRef());
        } catch (DbAccessException e) {
            throw new BusinessException(DsErrorCode.CONNECTION_FAILED, "数据源未就绪：只读密码未配置");
        }
        DsConfigHealthAdapter.HealthProbeParam param = DsConfigHealthAdapter.HealthProbeParam.builder()
                .dsId(dsId)
                .dbType(config.getDbType())
                .jdbcUrl(config.getJdbcUrl())
                .driverClass(config.getDriverClass())
                .readonlyUser(config.getReadonlyUser())
                .password(password)
                .whitelist(com.agenthub.ai.dbaccess.model.WhitelistRule.fromJson(config.getWhitelistJson()))
                // 可访问库集合原值透传（空/null 表示单库旧行为，不在本层回落 spaceName）
                .spaceNames(parseSpaceNames(config.getSpaceNamesJson()))
                .timeoutMs(properties.getHealthTimeoutMs())
                .build();
        HealthCheckResultVO vo = healthAdapter.probe(param);
        vo.setCheckType(CheckType.HEALTH_CHECK.name());
        recordHealth(dsId, CheckType.HEALTH_CHECK, vo, operatorId);
        updateAdminHealth(dsId, vo);
        if (withAudit) {
            auditService.writeAudit(dsId, AuditAction.HEALTH_CHECK.name(), operatorId, null,
                    "健康检查结果：" + vo.getCheckResult(), null,
                    "FAIL".equals(vo.getCheckResult()) ? "FAIL" : "SUCCESS", vo.getErrorMessage());
        }
        return vo;
    }

    /**
     * 健康历史分页（api-contract 10）。
     */
    public PageResult healthLogs(String dsId, Integer page, Integer pageSize, String checkType) {
        int p = page == null || page < 1 ? 1 : page;
        int ps = pageSize == null || pageSize < 1 ? properties.getDefaultPageSize()
                : Math.min(pageSize, properties.getPageSizeMax());
        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        IPage<DsConfigHealth> result = healthMapper.selectPageHealths(new Page<>(p, ps), dsId, checkType);
        List<HealthCheckResultVO> records = new ArrayList<>(result.getRecords().size());
        for (DsConfigHealth h : result.getRecords()) {
            records.add(toResultVO(h));
        }
        return new PageResult(result.getTotal(), records);
    }

    private void recordHealth(String dsId, CheckType checkType, HealthCheckResultVO vo, Long operatorId) {
        DsConfigHealth h = new DsConfigHealth();
        h.setDsId(dsId);
        h.setCheckType(checkType.name());
        h.setCheckResult(vo.getCheckResult());
        h.setDialectIdentified(vo.getDialectIdentified());
        h.setConnectionOk(boolToInt(vo.getConnectionOk()));
        h.setReadonlyOk(boolToInt(vo.getReadonlyOk()));
        h.setDictionaryOk(boolToInt(vo.getDictionaryOk()));
        h.setWhitelistOk(boolToInt(vo.getWhitelistOk()));
        h.setFailureItems(vo.getFailureItems() == null || vo.getFailureItems().isEmpty()
                ? null : JSON.toJSONString(vo.getFailureItems()));
        h.setErrorMessage(vo.getErrorMessage());
        h.setCostMs(vo.getCostMs());
        h.setOperatorId(operatorId == null ? 0L : operatorId);
        h.setCreateTime(new Date());
        healthMapper.insert(h);
    }

    private void updateAdminHealth(String dsId, HealthCheckResultVO vo) {
        DsConfigAdmin admin = adminMapper.selectOne(new LambdaQueryWrapper<DsConfigAdmin>()
                .eq(DsConfigAdmin::getDsId, dsId));
        if (admin == null) {
            return;
        }
        admin.setLastHealthState(vo.getCheckResult());
        admin.setLastHealthTime(new Date());
        // 最近健康摘要（脱敏，仅逐项 ok 布尔与方言，不含地址/凭据）
        admin.setLastHealthSummary(JSON.toJSONString(vo));
        adminMapper.updateById(admin);
    }

    public HealthCheckResultVO toResultVO(DsConfigHealth h) {
        HealthCheckResultVO vo = new HealthCheckResultVO();
        vo.setDsId(h.getDsId());
        vo.setCheckType(h.getCheckType());
        vo.setCheckResult(h.getCheckResult());
        vo.setDialectIdentified(h.getDialectIdentified());
        vo.setConnectionOk(intToBool(h.getConnectionOk()));
        vo.setReadonlyOk(intToBool(h.getReadonlyOk()));
        vo.setDictionaryOk(intToBool(h.getDictionaryOk()));
        vo.setWhitelistOk(intToBool(h.getWhitelistOk()));
        vo.setFailureItems(h.getFailureItems() == null || h.getFailureItems().isBlank()
                ? new ArrayList<>() : JSON.parseArray(h.getFailureItems(), String.class));
        vo.setErrorMessage(h.getErrorMessage());
        vo.setCostMs(h.getCostMs());
        vo.setCreateTime(h.getCreateTime());
        return vo;
    }

    private void validateTestRequest(DsTestConnectionRequest req) {
        if (req == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        }
        if (req.getDbType() == null || req.getDbType().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "dbType 必填");
        }
        if (!healthAdapter.isSupportedDbType(req.getDbType())) {
            throw new BusinessException(DsErrorCode.UNSUPPORTED_DB_TYPE,
                    "不支持的库类型（需先扩展 db-access-common Provider）");
        }
        if (req.getJdbcUrl() == null || !req.getJdbcUrl().startsWith("jdbc:")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "jdbcUrl 非法");
        }
        if (DbAccessConstants.EMBEDDED_CREDENTIAL_URL_PATTERN.matcher(req.getJdbcUrl()).find()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "jdbcUrl 禁止内嵌账密");
        }
        if (req.getReadonlyUser() == null || req.getReadonlyUser().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "readonlyUser 必填");
        }
        // OceanBase 族（仅校验新输入）：只读账号需形如 用户@租户（允许 用户@租户#集群）；
        // 非 OB 族（MySQL/Oracle）恒通过（零影响）；runHealthCheck/enable 链路不做该校验，保证存量零阻断
        if (!OceanBaseAccountValidator.isValid(req.getDbType(), req.getReadonlyUser())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, OceanBaseAccountValidator.TIP);
        }
        if (req.getPasswordForTest() == null || req.getPasswordForTest().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "passwordForTest 必填");
        }
        // 白名单 mode 缺省（null/blank）合法，按 WhitelistRule 缺省语义接管；非空必须 ∈ {ALLOW_ONLY, DENY_ONLY, ALL}
        if (req.getWhitelist() != null && req.getWhitelist().getMode() != null
                && !req.getWhitelist().getMode().isBlank()
                && !WhitelistRule.VALID_MODES.contains(req.getWhitelist().getMode().trim())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "白名单 mode 仅支持 ALLOW_ONLY/DENY_ONLY/ALL");
        }
    }

    /**
     * 解析一次性测试密码（api-contract 3.2 / data-model 规则 t_test_password_resolve_rule）：
     * 仅当 {@code raw.trim()} 整串匹配 {@link DbAccessConstants#PASSWORD_REF_PATTERN}（形如 {@code ${ENV_VAR}}）
     * 时走 {@link EnvPlaceholderResolver#resolvePasswordRef} 解析；其余一律视为明文原值透传（不 trim，保证零回归）。
     *
     * <p>空/空白由 {@link #validateTestRequest} 统一以 40000「passwordForTest 必填」拦截，本方法不重复处理。
     * 解析失败归一为 43004 且 message 固定脱敏文案，<b>禁止</b>拼接变量名/异常原文/堆栈（凭据禁出）。
     * 解析后明文仅存于局部变量并传入 probe 参数，不落日志/审计/健康记录/响应。</p>
     *
     * @param raw 请求携带的 passwordForTest 原值
     * @return 引用解析后的明文，或明文原值
     */
    private String resolveTestPassword(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String trimmed = raw.trim();
        if (DbAccessConstants.PASSWORD_REF_PATTERN.matcher(trimmed).matches()) {
            try {
                return placeholderResolver.resolvePasswordRef(trimmed);
            } catch (DbAccessException e) {
                throw new BusinessException(DsErrorCode.CONNECTION_FAILED, "连接配置未就绪：环境变量未注入或引用非法");
            }
        }
        return raw;
    }

    private int clampTimeout(Integer timeoutMs) {
        int t = timeoutMs == null || timeoutMs <= 0 ? properties.getHealthTimeoutMs() : timeoutMs;
        if (t > properties.getHealthTimeoutMaxMs()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "timeoutMs 不得超过 " + properties.getHealthTimeoutMaxMs() + "ms");
        }
        return t;
    }

    /**
     * space_names JSON 文本 → List（null/空串/非法/空列表均回落 null，语义：未声明库集合 → 单库旧行为）。
     */
    private List<String> parseSpaceNames(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<String> names = JSON.parseArray(json, String.class);
            return names == null || names.isEmpty() ? null : names;
        } catch (Exception e) {
            return null;
        }
    }

    private Integer boolToInt(Boolean v) {
        return Boolean.TRUE.equals(v) ? 1 : 0;
    }

    private Boolean intToBool(Integer v) {
        return Integer.valueOf(1).equals(v);
    }
}
