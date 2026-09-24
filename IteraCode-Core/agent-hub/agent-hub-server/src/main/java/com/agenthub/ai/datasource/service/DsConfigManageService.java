package com.agenthub.ai.datasource.service;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.datasource.adapter.SpaceLinker;
import com.agenthub.ai.datasource.config.DsProperties;
import com.agenthub.ai.datasource.constant.AuditAction;
import com.agenthub.ai.datasource.constant.DsErrorCode;
import com.agenthub.ai.datasource.constant.DsManageState;
import com.agenthub.ai.datasource.constant.SpaceBindState;
import com.agenthub.ai.datasource.dto.DsConfigPageQueryDTO;
import com.agenthub.ai.datasource.dto.DsConfigSaveDTO;
import com.agenthub.ai.datasource.entity.DsConfig;
import com.agenthub.ai.datasource.entity.DsConfigAdmin;
import com.agenthub.ai.datasource.entity.DsConfigHealth;
import com.agenthub.ai.datasource.exception.DsHealthCheckFailedException;
import com.agenthub.ai.datasource.mapper.DsConfigAdminMapper;
import com.agenthub.ai.datasource.mapper.DsConfigHealthMapper;
import com.agenthub.ai.datasource.mapper.DsConfigManageMapper;
import com.agenthub.ai.datasource.vo.AuditChange;
import com.agenthub.ai.datasource.vo.DsConfigDetailVO;
import com.agenthub.ai.datasource.vo.DsConfigVO;
import com.agenthub.ai.datasource.vo.DsStateResultVO;
import com.agenthub.ai.datasource.vo.HealthCheckResultVO;
import com.agenthub.ai.datasource.vo.SpaceBindResultVO;
import com.agenthub.ai.datasource.validation.JdbcUrlSchemaConsistencyChecker;
import com.agenthub.ai.dbaccess.constant.DbAccessConstants;
import com.agenthub.ai.dbaccess.model.ResourceLimits;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.dbaccess.validation.OceanBaseAccountValidator;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 数据源注册表管理服务（FR-BE-001/002/003，FR-003 联动编排）。
 *
 * <p>业务规则：BR-001 启用门槛 = 只读账号与密码引用已配置 + 白名单三模式区分
 * （未配置 null 默认全部表可访问；ALL/DENY_ONLY（含空清单）/ALLOW_ONLY+非空清单均满足；
 * 仅显式「ALLOW_ONLY + 空清单」视为白名单不满足并拦截）；BR-002 凭据脱敏（password 永不落库/日志/审计/响应）；
 * BR-003 ds_id 不可变+删除走软删；
 * 知识空间联动：数据源↔llm-wiki 空间为 N:1 共享复用（原 BR-004 一对一同名冲突拦截已废弃），
 * 联动成功写 SPACE_BIND SUCCESS 审计（摘要含共享数据源清单）；
 * 健康 FAIL/PARTIAL 禁止 ACTIVE（可 DRAFT）；db_type 无 Provider 可登记 DRAFT 不可启用；
 * llm-wiki 不可用降级保存不阻断注册主链路。</p>
 */
@Slf4j
@Service
public class DsConfigManageService {

    /** 可访问库集合数量上限（防滥用） */
    private static final int SPACE_NAMES_MAX_SIZE = 50;

    /** 单个可访问库名长度上限 */
    private static final int SPACE_NAME_MAX_LENGTH = 128;

    /** 可访问库名允许字符：字母数字/下划线/中划线（字符集保留点号仅用于兼容历史数据，新建库名应填纯库名/Owner 不含点号） */
    private static final Pattern SPACE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]+$");

    private final DsConfigManageMapper configManageMapper;
    private final DsConfigAdminMapper adminMapper;
    private final DsConfigHealthMapper healthMapper;
    private final DsAuditService auditService;
    private final DsHealthCheckService healthCheckService;
    private final SpaceLinker spaceLinker;
    private final DsProperties properties;
    private final com.agenthub.ai.datasource.adapter.DsConfigHealthAdapter healthAdapter;
    private final DataSourceRegistry dataSourceRegistry;

    public DsConfigManageService(DsConfigManageMapper configManageMapper,
                                 DsConfigAdminMapper adminMapper,
                                 DsConfigHealthMapper healthMapper,
                                 DsAuditService auditService,
                                 DsHealthCheckService healthCheckService,
                                 SpaceLinker spaceLinker,
                                 DsProperties properties,
                                 com.agenthub.ai.datasource.adapter.DsConfigHealthAdapter healthAdapter,
                                 DataSourceRegistry dataSourceRegistry) {
        this.configManageMapper = configManageMapper;
        this.adminMapper = adminMapper;
        this.healthMapper = healthMapper;
        this.auditService = auditService;
        this.healthCheckService = healthCheckService;
        this.spaceLinker = spaceLinker;
        this.properties = properties;
        this.healthAdapter = healthAdapter;
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /**
     * 数据源状态/配置变更后使 dbaccess 运行时连接池缓存失效（下次 resolve 依据 t_ds_config 重建）。
     *
     * <p>DataSourceRegistry 的 contextCache 按 dsId 缓存连接池，管理页 register/update/enable/disable/delete
     * 成功后若不失效会继续使用旧池/旧白名单。无 dbaccess 运行时（单测/降级）时 dataSourceRegistry 可为 null。</p>
     */
    private void invalidateRuntimeCache(String dsId) {
        if (dataSourceRegistry == null) {
            return;
        }
        try {
            dataSourceRegistry.invalidate(dsId);
        } catch (Exception e) {
            // 缓存失效失败不影响管理主链路（下次 resolve 仍可命中 DB 最新配置）
            log.warn("数据源运行时缓存失效异常 dsId={}", dsId, e);
        }
    }

    // ==================== 注册 ====================

    /**
     * 注册数据源（api-contract 1）：默认 DRAFT，可选联动 llm-wiki 建/确认空间。
     */
    @Transactional(rollbackFor = Exception.class)
    public DsConfigVO register(DsConfigSaveDTO dto, Long operatorId) {
        validateSaveDto(dto);
        // BR-003 ds_id 不可变 + 冲突拒绝覆盖
        if (configManageMapper.selectCount(new LambdaQueryWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dto.getDsId())) > 0) {
            throw new BusinessException(DsErrorCode.DS_ID_CONFLICT, "数据源 " + dto.getDsId() + " 已存在，禁止覆盖");
        }

        String targetSpace = dto.getSpaceName() == null ? null : dto.getSpaceName().trim();
        boolean autoCreate = Boolean.TRUE.equals(dto.getAutoCreateSpace());
        // FR-003：autoCreateSpace 且未提供空间名时按 ds 建议生成空间名（可与既有数据源共享同名空间）
        if (autoCreate && (targetSpace == null || targetSpace.isBlank())) {
            targetSpace = dto.getDsId();
        }

        Date now = new Date();
        DsConfig config = new DsConfig();
        applySaveDto(config, dto, now);
        config.setSpaceName(targetSpace == null || targetSpace.isBlank() ? null : targetSpace);
        // 注册默认 DRAFT（status=0）
        config.setStatus(DsConfigAdminSync.toConfigStatus(DsManageState.DRAFT));
        config.setVersion(0);
        config.setIsDeleted(0);
        configManageMapper.insert(config);

        DsConfigAdmin admin = new DsConfigAdmin();
        admin.setDsId(config.getDsId());
        admin.setManageState(DsManageState.DRAFT.name());
        admin.setSpaceBindState(SpaceBindState.NOT_APPLIED.name());
        admin.setLastHealthState("UNCHECKED");
        admin.setCreatorId(operatorId == null ? 0L : operatorId);
        admin.setCreateTime(now);
        admin.setUpdateTime(now);
        admin.setIsDeleted(0);
        adminMapper.insert(admin);

        auditService.writeAudit(config.getDsId(), AuditAction.REGISTER.name(), operatorId, null,
                "注册数据源（DRAFT）", registerFieldDetail(), "SUCCESS", null);

        // FR-003 可选空间联动：同名空间直接共享复用（不再判占用冲突），llm-wiki 不可用降级不阻断注册主链路
        if (autoCreate && targetSpace != null) {
            SpaceBindResultVO bindResult = spaceLinker.bindOrCreate(config.getDsId(), targetSpace, true, null);
            writeSpaceBindSuccessAudit(config.getDsId(), operatorId, bindResult);
        }
        // 打通 dbaccess 运行时：新登记的数据源使旧缓存失效（DRAFT 无池，调用幂等）
        invalidateRuntimeCache(config.getDsId());
        return toVO(config, loadAdmin(config.getDsId()));
    }

    // ==================== 修改 ====================

    /**
     * 修改数据源（api-contract 2）：ds_id 不可变、version 乐观锁；连接性字段变更回 DRAFT。
     *
     * <p>失败路径（版本冲突/参数非法等）补写 biz_result=FAIL 审计（FR-BE-003，独立事务提交不随回滚丢失）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public DsConfigVO update(String dsId, DsConfigSaveDTO dto, Long operatorId) {
        try {
            return updateInternal(dsId, dto, operatorId);
        } catch (BusinessException e) {
            writeFailAudit(dsId, AuditAction.UPDATE.name(), operatorId, "修改数据源失败", e.getMessage());
            throw e;
        }
    }

    /** update 业务实现（对外方法负责事务边界与失败审计包裹，本方法只关心状态变更语义） */
    private DsConfigVO updateInternal(String dsId, DsConfigSaveDTO dto, Long operatorId) {
        if (dto == null || dto.getDsId() == null || !dsId.equals(dto.getDsId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "路径 ds_id 与请求体 ds_id 必须一致（不可变更 BR-003）");
        }
        DsConfig old = configManageMapper.selectOne(new LambdaQueryWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId)
                .eq(DsConfig::getIsDeleted, 0));
        if (old == null) {
            throw new BusinessException(DsErrorCode.DS_NOT_FOUND, "数据源不存在或已删除");
        }
        if (dto.getVersion() == null || !Objects.equals(old.getVersion(), dto.getVersion())) {
            throw new BusinessException(DsErrorCode.INVALID_STATE, "版本冲突，请刷新后重试");
        }
        validateSaveDto(dto);

        Date now = new Date();
        UpdateDiff diff = computeUpdateDiff(old, dto);
        // 连接性字段变更 → 回 DRAFT 需重检；否则保持原状态
        DsManageState current = DsManageState.parse(loadAdmin(dsId).getManageState());
        DsManageState nextState = diff.connectivityChanged || current == null ? DsManageState.DRAFT : current;

        int nextVersion = old.getVersion() + 1;
        LambdaUpdateWrapper<DsConfig> uw = new LambdaUpdateWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId)
                .eq(DsConfig::getVersion, old.getVersion())
                .eq(DsConfig::getIsDeleted, 0)
                .set(DsConfig::getDsName, dto.getDsName())
                .set(DsConfig::getSystemCode, blankToNull(dto.getSystemCode()))
                .set(DsConfig::getDbType, dto.getDbType())
                .set(DsConfig::getJdbcUrl, dto.getJdbcUrl())
                .set(DsConfig::getDriverClass, blankToNull(dto.getDriverClass()))
                .set(DsConfig::getReadonlyUser, dto.getReadonlyUser())
                .set(DsConfig::getReadonlyPasswordRef, dto.getReadonlyPasswordRef().trim())
                .set(DsConfig::getOwnerGroup, blankToNull(dto.getOwnerGroup()))
                .set(DsConfig::getLimitsJson, dto.getLimits() == null ? null : JSON.toJSONString(dto.getLimits()))
                .set(DsConfig::getRemark, blankToNull(dto.getRemark()))
                .set(DsConfig::getStatus, DsConfigAdminSync.toConfigStatus(nextState))
                .set(DsConfig::getVersion, nextVersion)
                .set(DsConfig::getUpdateTime, now);
        // space_name / whitelist / space_names 单独设置（可能置空）
        String targetSpace = dto.getSpaceName() == null ? null : dto.getSpaceName().trim();
        uw.set(DsConfig::getSpaceName, blankToNull(targetSpace));
        uw.set(DsConfig::getWhitelistJson, dto.getWhitelist() == null ? null : dto.getWhitelist().toJson());
        uw.set(DsConfig::getSpaceNamesJson, dto.getSpaceNames() == null ? null : JSON.toJSONString(dto.getSpaceNames()));
        if (configManageMapper.update(null, uw) == 0) {
            throw new BusinessException(DsErrorCode.INVALID_STATE, "版本冲突，请刷新后重试");
        }

        // admin 状态同步
        LambdaUpdateWrapper<DsConfigAdmin> adminUw = new LambdaUpdateWrapper<DsConfigAdmin>()
                .eq(DsConfigAdmin::getDsId, dsId)
                .set(DsConfigAdmin::getManageState, nextState.name())
                .set(DsConfigAdmin::getUpdateTime, now);
        adminMapper.update(null, adminUw);

        auditService.writeAudit(dsId, AuditAction.UPDATE.name(), operatorId, null,
                diff.changes.isEmpty() ? "提交更新（无字段变化）" : "变更字段：" + diff.getSummary(),
                diff.changes, "SUCCESS", null);

        // 更新了空间名且请求自动联动 → 触发空间重绑（同名空间共享复用，降级不阻断）
        boolean spaceChanged = !Objects.equals(nz(old.getSpaceName()), nz(targetSpace));
        if (spaceChanged && Boolean.TRUE.equals(dto.getAutoCreateSpace()) && targetSpace != null && !targetSpace.isBlank()) {
            SpaceBindResultVO bindResult = spaceLinker.bindOrCreate(dsId, targetSpace, true, null);
            writeSpaceBindSuccessAudit(dsId, operatorId, bindResult);
        }
        // 连接性/白名单/limits 等变更后失效运行时缓存，下次 resolve 按 t_ds_config 最新配置重建
        invalidateRuntimeCache(dsId);
        return toVO(configManageMapper.selectById(old.getId()), loadAdmin(dsId));
    }

    // ==================== 删除/启停 ====================

    /**
     * 软删除（api-contract 3）：DELETED + is_deleted=1，历史保留；对已 DELETED 重复删除返回 43003。
     *
     * <p>失败路径（重复删除/不存在）补写 biz_result=FAIL 审计（FR-BE-003，独立事务提交不随回滚丢失）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public DsStateResultVO delete(String dsId, String remark, Long operatorId) {
        try {
            return deleteInternal(dsId, remark, operatorId);
        } catch (BusinessException e) {
            writeFailAudit(dsId, AuditAction.DELETE.name(), operatorId, "删除数据源失败", e.getMessage());
            throw e;
        }
    }

    /** delete 业务实现（失败审计包裹见 delete） */
    private DsStateResultVO deleteInternal(String dsId, String remark, Long operatorId) {
        DsConfig config = requireConfig(dsId);
        if (Integer.valueOf(1).equals(config.getIsDeleted())) {
            throw new BusinessException(DsErrorCode.INVALID_STATE, "数据源已删除，禁止重复删除");
        }
        Date now = new Date();
        configManageMapper.update(null, new LambdaUpdateWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId)
                .set(DsConfig::getStatus, 0)
                .set(DsConfig::getIsDeleted, 1)
                .set(DsConfig::getUpdateTime, now));
        adminMapper.update(null, new LambdaUpdateWrapper<DsConfigAdmin>()
                .eq(DsConfigAdmin::getDsId, dsId)
                .set(DsConfigAdmin::getManageState, DsManageState.DELETED.name())
                .set(DsConfigAdmin::getIsDeleted, 1)
                .set(DsConfigAdmin::getUpdateTime, now));
        auditService.writeAudit(dsId, AuditAction.DELETE.name(), operatorId, null,
                remark == null || remark.isBlank() ? "删除数据源（软删）" : "删除原因：" + remark,
                null, "SUCCESS", null);
        // 软删后失效运行时缓存，避免已删除数据源仍被 resolve 命中旧连接池
        invalidateRuntimeCache(dsId);
        DsStateResultVO vo = new DsStateResultVO();
        vo.setDsId(dsId);
        vo.setManageState(DsManageState.DELETED.name());
        vo.setWarn("若该数据源存在采集/检索/反馈下游任务，请同步在对应模块处理");
        return vo;
    }

    /**
     * 启用（api-contract 3）：BR-001 前置检查 + DRAFT/DISABLED→ACTIVE（健康检查 PASS；force=true 可跳过）。
     *
     * <p>健康检查由 {@link DsHealthCheckService#runHealthCheck} 在 REQUIRES_NEW 独立事务中探测并留痕，
     * FAIL 抛 43005 回滚的是外层状态翻转事务，健康历史与 admin.last_health_* 摘要不随回滚丢失（spec A-2 验收 3/5）。</p>
     *
     * <p>失败路径（BR-001 缺失/健康 FAIL 等）补写 biz_result=FAIL 审计
     * （FR-BE-003，独立事务提交不随回滚丢失）。</p>
     *
     * @throws DsHealthCheckFailedException 健康未通过（43005，data 带诊断）
     */
    @Transactional(rollbackFor = Exception.class)
    public DsStateResultVO enable(String dsId, Boolean force, Long operatorId) {
        try {
            return enableInternal(dsId, force, operatorId);
        } catch (DsHealthCheckFailedException e) {
            writeFailAudit(dsId, AuditAction.ENABLE.name(), operatorId,
                    "启用数据源失败：健康检查未通过", e.getMessage());
            throw e;
        } catch (BusinessException e) {
            writeFailAudit(dsId, AuditAction.ENABLE.name(), operatorId, "启用数据源失败", e.getMessage());
            throw e;
        }
    }

    /** enable 业务实现（失败审计包裹见 enable） */
    private DsStateResultVO enableInternal(String dsId, Boolean force, Long operatorId) {
        DsConfig config = requireConfig(dsId);
        DsConfigAdmin admin = requireAdmin(dsId);
        DsManageState state = DsManageState.parse(admin.getManageState());
        if (state == null || state == DsManageState.ACTIVE || state == DsManageState.DELETED) {
            throw new BusinessException(DsErrorCode.INVALID_STATE, "当前状态不可启用（仅 DRAFT/DISABLED）");
        }
        // BR-001：只读账号+密码引用已配置；白名单按三模式区分（未配置 null 默认全部表可访问；
        // ALL/DENY_ONLY（含空清单）/ALLOW_ONLY+非空清单均满足；仅显式 ALLOW_ONLY+空清单视为不满足并拦截）
        WhitelistRule whitelist = WhitelistRule.fromJson(config.getWhitelistJson());
        boolean readonlyConfigured = isNotBlank(config.getReadonlyUser()) && isNotBlank(config.getReadonlyPasswordRef());
        if (!readonlyConfigured) {
            throw new BusinessException(DsErrorCode.READONLY_POLICY_VIOLATION,
                    "启用前必须先配置只读账号与密码引用（BR-001）");
        }
        if (whitelist != null && whitelist.isExplicitRejectAll()) {
            throw new BusinessException(DsErrorCode.READONLY_POLICY_VIOLATION,
                    "已选「仅允许下列表」但表清单为空，启用会被全部拒绝（BR-001），请填写允许表名或切换为其他访问模式");
        }
        // 知识空间已放宽为 N:1 共享复用：不再校验目标空间是否被其他数据源持有，允许多个 ACTIVE 同空间
        HealthCheckResultVO health = null;
        if (!Boolean.TRUE.equals(force)) {
            // 前置健康检查：FAIL/PARTIAL 禁止 ACTIVE（health 记录由 runHealthCheck 独立事务留痕）
            health = healthCheckService.runHealthCheck(dsId, operatorId, false);
            if (health == null || !"PASS".equals(health.getCheckResult())) {
                throw new DsHealthCheckFailedException(health,
                        "健康检查未通过，不可启用：" + (health == null ? "" : health.getErrorMessage()));
            }
        }
        Date now = new Date();
        configManageMapper.update(null, new LambdaUpdateWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId)
                .set(DsConfig::getStatus, 1)
                .set(DsConfig::getUpdateTime, now));
        adminMapper.update(null, new LambdaUpdateWrapper<DsConfigAdmin>()
                .eq(DsConfigAdmin::getDsId, dsId)
                .set(DsConfigAdmin::getManageState, DsManageState.ACTIVE.name())
                .set(DsConfigAdmin::getUpdateTime, now));
        auditService.writeAudit(dsId, AuditAction.ENABLE.name(), operatorId, null,
                Boolean.TRUE.equals(force) ? "启用数据源（强制跳过健康检查）" : "启用数据源（健康检查 PASS）",
                null, "SUCCESS", null);
        // 启用后失效运行时缓存，保证下次 resolve 按最新 status/配置重建连接池
        invalidateRuntimeCache(dsId);
        DsStateResultVO vo = new DsStateResultVO();
        vo.setDsId(dsId);
        vo.setManageState(DsManageState.ACTIVE.name());
        vo.setHealth(health);
        return vo;
    }

    /**
     * 停用（api-contract 5）：ACTIVE/DRAFT→DISABLED。
     *
     * <p>失败路径（重复停用/不存在）补写 biz_result=FAIL 审计（FR-BE-003，独立事务提交不随回滚丢失）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public DsStateResultVO disable(String dsId, String remark, Long operatorId) {
        try {
            return disableInternal(dsId, remark, operatorId);
        } catch (BusinessException e) {
            writeFailAudit(dsId, AuditAction.DISABLE.name(), operatorId, "停用数据源失败", e.getMessage());
            throw e;
        }
    }

    /** disable 业务实现（失败审计包裹见 disable） */
    private DsStateResultVO disableInternal(String dsId, String remark, Long operatorId) {
        DsConfig config = requireConfig(dsId);
        DsConfigAdmin admin = requireAdmin(dsId);
        DsManageState state = DsManageState.parse(admin.getManageState());
        if (state == null || state == DsManageState.DISABLED || state == DsManageState.DELETED) {
            throw new BusinessException(DsErrorCode.INVALID_STATE, "当前状态不可停用（仅 ACTIVE/DRAFT）");
        }
        Date now = new Date();
        configManageMapper.update(null, new LambdaUpdateWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId)
                .set(DsConfig::getStatus, 0)
                .set(DsConfig::getUpdateTime, now));
        adminMapper.update(null, new LambdaUpdateWrapper<DsConfigAdmin>()
                .eq(DsConfigAdmin::getDsId, dsId)
                .set(DsConfigAdmin::getManageState, DsManageState.DISABLED.name())
                .set(DsConfigAdmin::getUpdateTime, now));
        auditService.writeAudit(dsId, AuditAction.DISABLE.name(), operatorId, null,
                remark == null || remark.isBlank() ? "停用数据源" : "停用原因：" + remark,
                null, "SUCCESS", null);
        // 停用后失效运行时缓存，避免停用数据源仍被 resolve 命中旧连接池
        invalidateRuntimeCache(dsId);
        DsStateResultVO vo = new DsStateResultVO();
        vo.setDsId(dsId);
        vo.setManageState(DsManageState.DISABLED.name());
        return vo;
    }

    // ==================== 查询 ====================

    /**
     * 脱敏详情（api-contract 6）：配置 + 管理态 + 最近健康摘要。
     */
    public DsConfigDetailVO detail(String dsId) {
        DsConfig config = configManageMapper.selectOne(new LambdaQueryWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId)
                .eq(DsConfig::getIsDeleted, 0));
        if (config == null) {
            throw new BusinessException(DsErrorCode.DS_NOT_FOUND, "数据源不存在或已删除");
        }
        DsConfigAdmin admin = requireAdmin(dsId);
        DsConfigDetailVO vo = new DsConfigDetailVO();
        copyToVO(vo, config, admin);
        DsConfigHealth latest = healthMapper.selectLatestByDs(dsId);
        if (latest != null) {
            vo.setLastHealth(healthCheckService.toResultVO(latest));
        }
        return vo;
    }

    /**
     * 分页列表（api-contract 7，脱敏；默认排除 DELETED）。
     */
    public PageResult page(DsConfigPageQueryDTO query) {
        DsConfigPageQueryDTO q = query == null ? new DsConfigPageQueryDTO() : query;
        if (q.getManageState() != null && !q.getManageState().isBlank()
                && DsManageState.parse(q.getManageState()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "manageState 取值非法");
        }
        int p = q.getPage() == null || q.getPage() < 1 ? 1 : q.getPage();
        int ps = q.getPageSize() == null || q.getPageSize() < 1 ? properties.getDefaultPageSize()
                : Math.min(q.getPageSize(), properties.getPageSizeMax());
        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        IPage<DsConfigVO> page = configManageMapper.selectPageConfigs(new Page<>(p, ps), q);
        for (DsConfigVO vo : page.getRecords()) {
            vo.setWhitelist(WhitelistRule.fromJson(vo.getWhitelistJson()));
            vo.setLimits(parseLimits(vo.getLimitsJson()));
            vo.setSpaceNames(parseSpaceNames(vo.getSpaceNamesJson()));
        }
        return new PageResult(page.getTotal(), page.getRecords());
    }

    // ==================== 内部工具 ====================

    /**
     * 失败路径补写 biz_result=FAIL 审计（FR-BE-003 完整性）。
     *
     * <p>状态类动作失败抛出的业务异常会使外层事务回滚，若 FAIL 审计写在同一事务会一并丢失；
     * {@link DsAuditService#writeAuditFailure} 以 REQUIRES_NEW 独立提交，提前留痕失败原因。
     * 审计写入自身失败仅记录日志，不改变原异常语义。</p>
     */
    private void writeFailAudit(String dsId, String action, Long operatorId, String summary, String error) {
        try {
            auditService.writeAuditFailure(dsId, action, operatorId, summary, error);
        } catch (Exception e) {
            log.warn("失败审计写入异常（不影响主链路异常语义）dsId={} action={}", dsId, action, e);
        }
    }

    /**
     * 空间联动成功（BOUND）时写 SPACE_BIND SUCCESS 审计（api-contract §3 接口 1/2）。
     *
     * <p>数据源↔知识空间为 N:1 共享复用：复用其他数据源持有的同名空间属正常成功路径，
     * 摘要按「自动创建 / 复用共享（含共享数据源清单）」区分，清单仅列 dsId 且长度受控（≤512，脱敏）。</p>
     */
    private void writeSpaceBindSuccessAudit(String dsId, Long operatorId, SpaceBindResultVO bindResult) {
        if (bindResult == null || !SpaceBindState.BOUND.name().equals(bindResult.getSpaceBindState())) {
            return;
        }
        auditService.writeAudit(dsId, AuditAction.SPACE_BIND.name(), operatorId, null,
                DsSpaceSharingService.buildSpaceBindSummary(Boolean.TRUE.equals(bindResult.getCreated()),
                        bindResult.getSharedDsList()),
                null, "SUCCESS", null);
    }

    private void validateSaveDto(DsConfigSaveDTO dto) {
        if (dto == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        }
        if (dto.getDsId() == null || !dto.getDsId().matches(DbAccessConstants.DS_ID_PATTERN)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 必填且格式为 ^[a-z0-9][a-z0-9-_]*$");
        }
        if (isBlank(dto.getDsName())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "dsName 必填");
        }
        if (isBlank(dto.getDbType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "dbType 必填");
        }
        if (isBlank(dto.getJdbcUrl()) || !dto.getJdbcUrl().startsWith("jdbc:")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "jdbcUrl 非法");
        }
        if (DbAccessConstants.EMBEDDED_CREDENTIAL_URL_PATTERN.matcher(dto.getJdbcUrl()).find()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "jdbcUrl 禁止内嵌账密");
        }
        if (isBlank(dto.getReadonlyUser())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "readonlyUser 必填");
        }
        // OceanBase 族（仅校验新输入）：只读账号需形如 用户@租户（允许 用户@租户#集群）；
        // 非 OB 族（MySQL/Oracle）恒通过；register/update 共用本校验链，存量已登记配置的健康检查/启用不受影响
        if (!OceanBaseAccountValidator.isValid(dto.getDbType(), dto.getReadonlyUser())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, OceanBaseAccountValidator.TIP);
        }
        if (isBlank(dto.getReadonlyPasswordRef())
                || !DbAccessConstants.PASSWORD_REF_PATTERN.matcher(dto.getReadonlyPasswordRef().trim()).matches()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "readonlyPasswordRef 必须为 ${ENV} 引用，禁止明文密码");
        }
        // 白名单 mode 缺省（null/blank）合法，按 WhitelistRule 缺省语义接管；非空必须 ∈ {ALLOW_ONLY, DENY_ONLY, ALL}
        if (dto.getWhitelist() != null && dto.getWhitelist().getMode() != null
                && !dto.getWhitelist().getMode().isBlank()
                && !WhitelistRule.VALID_MODES.contains(dto.getWhitelist().getMode().trim())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "白名单 mode 仅支持 ALLOW_ONLY/DENY_ONLY/ALL");
        }
        if (dto.getDsName() != null && dto.getDsName().length() > 128) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "dsName 长度超限");
        }
        if (dto.getSpaceName() != null && dto.getSpaceName().length() > 128) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "spaceName 长度超限");
        }
        // 可访问库集合 spaceNames 合法性：库名非空白、≤128、仅允许 A-Za-z0-9_.-（字符集保留点号仅为兼容历史数据；spaceNames 应填纯库名/Schema Owner，不含点号：MySQL/OceanBase-MySQL 填 database 名，Oracle/OceanBase-Oracle 填 schema/Owner 名，不允许空格）；数量 ≤50 防滥用
        if (dto.getSpaceNames() != null && !dto.getSpaceNames().isEmpty()) {
            if (dto.getSpaceNames().size() > SPACE_NAMES_MAX_SIZE) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "可访问库集合数量超限：最多允许 " + SPACE_NAMES_MAX_SIZE + " 个，当前 " + dto.getSpaceNames().size() + " 个");
            }
            for (String raw : dto.getSpaceNames()) {
                if (raw == null || raw.trim().isEmpty()) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "可访问库集合存在空白库名，请去除后重试");
                }
                if (raw.length() > SPACE_NAME_MAX_LENGTH) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,
                            "可访问库名长度超限（≤" + SPACE_NAME_MAX_LENGTH + "）：" + raw);
                }
                if (!SPACE_NAME_PATTERN.matcher(raw).matches()) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,
                            "可访问库名非法，仅允许字母/数字/下划线/中划线且不含空格（请填写纯库名/Schema Owner，不含点号：MySQL/OceanBase-MySQL 填 database 名，Oracle/OceanBase-Oracle 填 schema/Owner 名）：" + raw);
                }
            }
        }
        // 注册/修改均要求 db_type 在枚举且 module-002 Provider 就绪（无 Provider 可登记 DRAFT 但不可启用）
        if (!healthAdapter.isSupportedDbType(dto.getDbType())) {
            throw new BusinessException(DsErrorCode.UNSUPPORTED_DB_TYPE,
                    "不支持的库类型或 Provider 未就绪（需先扩展 db-access-common Provider）");
        }
        // 连接串末尾默认库 与 可访问库集合一致性判定：仅诊断（warning，非阻断）；
        // 服务端不产出提示、不回显库名（Q5），故此处仅记结果类别，库名一致性提示由前端基于用户自身输入展示
        if (JdbcUrlSchemaConsistencyChecker.check(dto.getDbType(), dto.getJdbcUrl(), dto.getSpaceNames())
                == JdbcUrlSchemaConsistencyChecker.Result.INCONSISTENT) {
            log.debug("ds-save: 连接串默认库不在可访问库集合中（仅诊断，不阻断保存）");
        }
    }

    private void applySaveDto(DsConfig config, DsConfigSaveDTO dto, Date now) {
        config.setDsId(dto.getDsId());
        config.setDsName(dto.getDsName());
        config.setSystemCode(blankToNull(dto.getSystemCode()));
        config.setDbType(dto.getDbType());
        config.setJdbcUrl(dto.getJdbcUrl());
        config.setDriverClass(blankToNull(dto.getDriverClass()));
        config.setReadonlyUser(dto.getReadonlyUser());
        config.setReadonlyPasswordRef(dto.getReadonlyPasswordRef().trim());
        config.setOwnerGroup(blankToNull(dto.getOwnerGroup()));
        // spaceNames 可访问库集合按 whitelist 同款 JSON 文本存取（禁止逗号字符串）
        config.setSpaceNamesJson(dto.getSpaceNames() == null ? null : JSON.toJSONString(dto.getSpaceNames()));
        config.setWhitelistJson(dto.getWhitelist() == null ? null : dto.getWhitelist().toJson());
        config.setLimitsJson(dto.getLimits() == null ? null : JSON.toJSONString(dto.getLimits()));
        config.setRemark(blankToNull(dto.getRemark()));
        config.setCreateTime(now);
        config.setUpdateTime(now);
    }

    private UpdateDiff computeUpdateDiff(DsConfig old, DsConfigSaveDTO dto) {
        UpdateDiff diff = new UpdateDiff();
        String oldWhitelistJson = old.getWhitelistJson();
        String newWhitelistJson = dto.getWhitelist() == null ? null : dto.getWhitelist().toJson();
        String oldSpace = nz(old.getSpaceName());
        String newSpace = nz(dto.getSpaceName() == null ? null : dto.getSpaceName().trim());

        diffConnectivity(diff, "db_type", old.getDbType(), dto.getDbType());
        diffConnectivity(diff, "jdbc_url", old.getJdbcUrl(), dto.getJdbcUrl());
        diffConnectivity(diff, "driver_class", old.getDriverClass(), blankToNull(dto.getDriverClass()));
        diffConnectivity(diff, "readonly_user", old.getReadonlyUser(), dto.getReadonlyUser());
        if (!nz(old.getReadonlyPasswordRef()).equals(nz(dto.getReadonlyPasswordRef()))) {
            diff.connectivityChanged = true;
            AuditChange change = new AuditChange();
            change.setField("readonly_password_ref");
            change.setChanged(true); // 敏感字段只记 changed 标记（BR-002）
            diff.changes.add(change);
            diff.summaryParts.add("readonly_password_ref");
        }
        diffConnectivity(diff, "whitelist", oldWhitelistJson, newWhitelistJson);
        // 可访问库集合属访问边界配置，与 whitelist 同等处理：变更回 DRAFT 重检 + 审计留痕
        String oldSpaceNamesJson = old.getSpaceNamesJson();
        String newSpaceNamesJson = dto.getSpaceNames() == null ? null : JSON.toJSONString(dto.getSpaceNames());
        diffConnectivity(diff, "space_names", oldSpaceNamesJson, newSpaceNamesJson);
        diffNormal(diff, "ds_name", old.getDsName(), dto.getDsName());
        diffNormal(diff, "system_code", old.getSystemCode(), blankToNull(dto.getSystemCode()));
        diffNormal(diff, "owner_group", old.getOwnerGroup(), blankToNull(dto.getOwnerGroup()));
        diffNormal(diff, "remark", old.getRemark(), blankToNull(dto.getRemark()));
        diffNormal(diff, "space_name", oldSpace, newSpace);
        // limits 变更不重置健康（非连接性），仅记录
        String oldLimits = old.getLimitsJson();
        String newLimits = dto.getLimits() == null ? null : JSON.toJSONString(dto.getLimits());
        if (!Objects.equals(nz(oldLimits), nz(newLimits))) {
            diff.changes.add(buildChange("limits", oldLimits, newLimits));
            diff.summaryParts.add("limits");
        }
        return diff;
    }

    private void diffConnectivity(UpdateDiff diff, String field, String oldV, String newV) {
        if (!Objects.equals(nz(oldV), nz(newV))) {
            diff.connectivityChanged = true;
            diff.changes.add(buildChange(field, oldV, newV));
            diff.summaryParts.add(field);
        }
    }

    private void diffNormal(UpdateDiff diff, String field, String oldV, String newV) {
        if (!Objects.equals(nz(oldV), nz(newV))) {
            diff.changes.add(buildChange(field, oldV, newV));
            diff.summaryParts.add(field);
        }
    }

    private AuditChange buildChange(String field, String oldV, String newV) {
        AuditChange change = new AuditChange();
        change.setField(field);
        change.setBefore(oldV);
        change.setAfter(newV);
        change.setChanged(true);
        return change;
    }

    private List<AuditChange> registerFieldDetail() {
        // REGISTER 记字段清单（不含任何密码值）
        String[] fields = {"ds_id", "ds_name", "system_code", "db_type", "jdbc_url",
                "driver_class", "readonly_user", "readonly_password_ref", "owner_group",
                "space_name", "space_names", "whitelist", "limits", "remark"};
        List<AuditChange> list = new ArrayList<>();
        for (String f : fields) {
            AuditChange c = new AuditChange();
            c.setField(f);
            c.setChanged(true);
            list.add(c);
        }
        return list;
    }

    private DsConfigVO toVO(DsConfig config, DsConfigAdmin admin) {
        DsConfigVO vo = new DsConfigVO();
        copyToVO(vo, config, admin);
        return vo;
    }

    private void copyToVO(DsConfigVO vo, DsConfig config, DsConfigAdmin admin) {
        vo.setDsId(config.getDsId());
        vo.setDsName(config.getDsName());
        vo.setSystemCode(config.getSystemCode());
        vo.setDbType(config.getDbType());
        vo.setJdbcUrl(config.getJdbcUrl());
        vo.setDriverClass(config.getDriverClass());
        vo.setReadonlyUser(config.getReadonlyUser());
        vo.setReadonlyPasswordRef(config.getReadonlyPasswordRef());
        vo.setOwnerGroup(config.getOwnerGroup());
        vo.setSpaceName(config.getSpaceName());
        vo.setSpaceNames(parseSpaceNames(config.getSpaceNamesJson()));
        vo.setWhitelist(WhitelistRule.fromJson(config.getWhitelistJson()));
        vo.setLimits(parseLimits(config.getLimitsJson()));
        vo.setStatus(config.getStatus());
        vo.setRemark(config.getRemark());
        vo.setVersion(config.getVersion());
        vo.setCreateTime(config.getCreateTime());
        vo.setUpdateTime(config.getUpdateTime());
        if (admin != null) {
            vo.setManageState(admin.getManageState());
            vo.setSpaceBindState(admin.getSpaceBindState());
            vo.setSpaceBindError(admin.getSpaceBindError());
            vo.setLastHealthState(admin.getLastHealthState());
            vo.setLastHealthTime(admin.getLastHealthTime());
        }
    }

    private ResourceLimits parseLimits(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json, ResourceLimits.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * space_names JSON 文本 → List（null/空串/非法均回落 null，与 whitelist 缺省语义一致：回落 spaceName 单库）。
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

    private DsConfigAdmin loadAdmin(String dsId) {
        DsConfigAdmin admin = adminMapper.selectOne(new LambdaQueryWrapper<DsConfigAdmin>()
                .eq(DsConfigAdmin::getDsId, dsId));
        if (admin == null) {
            throw new BusinessException(DsErrorCode.DS_NOT_FOUND, "数据源管理记录不存在");
        }
        return admin;
    }

    private DsConfig requireConfig(String dsId) {
        DsConfig config = configManageMapper.selectOne(new LambdaQueryWrapper<DsConfig>()
                .eq(DsConfig::getDsId, dsId));
        if (config == null) {
            throw new BusinessException(DsErrorCode.DS_NOT_FOUND, "数据源不存在");
        }
        return config;
    }

    private DsConfigAdmin requireAdmin(String dsId) {
        DsConfigAdmin admin = adminMapper.selectOne(new LambdaQueryWrapper<DsConfigAdmin>()
                .eq(DsConfigAdmin::getDsId, dsId));
        if (admin == null) {
            throw new BusinessException(DsErrorCode.DS_NOT_FOUND, "数据源管理记录不存在");
        }
        return admin;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    /** update 差异计算结构 */
    private static class UpdateDiff {

        private boolean connectivityChanged;
        private final List<AuditChange> changes = new ArrayList<>();
        private final List<String> summaryParts = new ArrayList<>();

        private String getSummary() {
            return String.join(",", summaryParts);
        }
    }
}
