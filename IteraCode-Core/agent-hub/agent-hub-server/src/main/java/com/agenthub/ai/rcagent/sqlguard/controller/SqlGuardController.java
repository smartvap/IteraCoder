package com.agenthub.ai.rcagent.sqlguard.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.rcagent.sqlguard.config.SqlGuardProperties;
import com.agenthub.ai.rcagent.sqlguard.constant.PolicyScope;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardErrorCode;
import com.agenthub.ai.rcagent.sqlguard.dto.SqlGuardAuditPageRequest;
import com.agenthub.ai.rcagent.sqlguard.dto.SqlGuardPolicySaveRequest;
import com.agenthub.ai.rcagent.sqlguard.dto.SqlGuardValidateRequest;
import com.agenthub.ai.rcagent.sqlguard.entity.SqlGuardAuditLog;
import com.agenthub.ai.rcagent.sqlguard.entity.SqlGuardPolicy;
import com.agenthub.ai.rcagent.sqlguard.exception.SqlGuardException;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardRequest;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardValidateOutcome;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardAuditService;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardPolicyService;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardService;
import com.agenthub.ai.rcagent.sqlguard.util.SqlTextUtil;
import com.agenthub.ai.rcagent.sqlguard.vo.SqlGuardAuditVO;
import com.agenthub.ai.rcagent.sqlguard.vo.SqlGuardPolicySaveResultVO;
import com.agenthub.ai.rcagent.sqlguard.vo.SqlGuardPolicyVO;
import com.agenthub.ai.rcagent.sqlguard.vo.SqlGuardValidateResultVO;
import com.alibaba.fastjson2.JSON;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * SQL 防护 HTTP 管理端点（4 个：debug/validate、policy/save、policy/list、audit/page）。
 */
@Tag(name = "SqlGuardController", description = "SQL 安全与验证防护（调试/策略/审计）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/sqlguard")
public class SqlGuardController {

    private final SqlGuardService sqlGuardService;
    private final SqlGuardPolicyService policyService;
    private final SqlGuardAuditService auditService;
    private final SqlGuardProperties properties;

    public SqlGuardController(SqlGuardService sqlGuardService,
                              SqlGuardPolicyService policyService,
                              SqlGuardAuditService auditService,
                              SqlGuardProperties properties) {
        this.sqlGuardService = sqlGuardService;
        this.policyService = policyService;
        this.auditService = auditService;
        this.properties = properties;
    }

    /** 接口 1 调试-全流程验证（explainOnly 语义=仅静态/计划，不执行业务查询） */
    @Operation(summary = "调试-全流程验证（不执行）")
    @PostMapping("/debug/validate")
    public BaseResponse<SqlGuardValidateResultVO> debugValidate(@RequestBody SqlGuardValidateRequest dto) {
        if (!properties.isDebugEnabled()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "调试端点已关闭（agenthub.sqlguard.debug-enabled=false）");
        }
        if (dto.getDsId() == null || dto.getDsId().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空");
        }
        if (dto.getSql() == null || dto.getSql().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "SQL 不能为空");
        }
        SqlGuardRequest request = SqlGuardRequest.builder()
                .dsId(dto.getDsId())
                .sql(dto.getSql())
                .dbType(dto.getDbType())
                .plan(dto.getPlan())
                .explainOnly(true)
                .build();
        com.agenthub.ai.rcagent.sqlguard.model.SqlGuardValidateOutcome outcome = sqlGuardService.validateOutcome(request);
        SqlGuardValidateResultVO vo = new SqlGuardValidateResultVO();
        vo.setReport(outcome.getReport());
        vo.setAuditId(outcome.getAuditId());
        return ResultUtils.success(vo);
    }

    /** 接口 2 保存安全策略（upsert，config_version+1 热加载） */
    @Operation(summary = "保存安全策略")
    @PostMapping("/policy/save")
    public BaseResponse<SqlGuardPolicySaveResultVO> policySave(@RequestBody SqlGuardPolicySaveRequest dto) {
        if (dto.getPolicyScope() == null || dto.getPolicyScope().isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "policy_scope 不能为空");
        }
        PolicyScope scope = PolicyScope.parse(dto.getPolicyScope());
        if (scope == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "policy_scope 非法");
        }
        if (scope == PolicyScope.DS && (dto.getDsId() == null || dto.getDsId().isBlank())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "DS 覆盖策略必须指定 ds_id");
        }
        SqlGuardPolicy entity = new SqlGuardPolicy();
        entity.setPolicyScope(scope.name());
        entity.setDsId(scope == PolicyScope.GLOBAL ? null : dto.getDsId());
        entity.setPolicyName(dto.getPolicyName());
        entity.setMaxRowsDefault(dto.getMaxRowsDefault());
        entity.setMaxSecondsDefault(dto.getMaxSecondsDefault());
        entity.setSelectStarPolicy(dto.getSelectStarPolicy());
        entity.setBlockEnabled(dto.getBlockEnabled());
        entity.setSensitivePolicy(dto.getSensitivePolicy());
        entity.setSensitiveSource(dto.getSensitiveSource());
        entity.setLowAutoEnabled(dto.getLowAutoEnabled());
        entity.setMvpReviewAll(dto.getMvpReviewAll());
        entity.setHighReviewRequired(dto.getHighReviewRequired());
        entity.setRepairMaxRounds(dto.getRepairMaxRounds());
        entity.setDialectWhitelist(SqlGuardPolicyService.whitelistToJson(dto.getDialectWhitelist()));
        entity.setRemark(dto.getRemark());
        SqlGuardPolicy saved = policyService.save(entity);
        SqlGuardPolicySaveResultVO vo = new SqlGuardPolicySaveResultVO();
        vo.setId(saved.getId());
        vo.setConfigVersion(saved.getConfigVersion());
        vo.setReload("RELOADED");
        return ResultUtils.success(vo);
    }

    /** 接口 3 查询安全策略（分页） */
    @Operation(summary = "查询安全策略")
    @GetMapping("/policy/list")
    public BaseResponse<PageResult> policyList(@RequestParam(required = false) String policyScope,
                                               @RequestParam(required = false) String dsId,
                                               @RequestParam(required = false, defaultValue = "1") Integer page,
                                               @RequestParam(required = false, defaultValue = "10") Integer pageSize) {
        PageResult result = policyService.list(policyScope, dsId, page == null ? 1 : page,
                pageSize == null ? 10 : Math.min(100, Math.max(1, pageSize)));
        List<SqlGuardPolicyVO> vos = new ArrayList<>();
        for (Object o : result.getRecords()) {
            if (o instanceof SqlGuardPolicy p) {
                SqlGuardPolicyVO vo = new SqlGuardPolicyVO();
                vo.setId(p.getId());
                vo.setPolicyScope(p.getPolicyScope());
                vo.setDsId(p.getDsId());
                vo.setPolicyName(p.getPolicyName());
                vo.setMaxRowsDefault(p.getMaxRowsDefault());
                vo.setMaxSecondsDefault(p.getMaxSecondsDefault());
                vo.setSelectStarPolicy(p.getSelectStarPolicy());
                vo.setBlockEnabled(p.getBlockEnabled());
                vo.setSensitivePolicy(p.getSensitivePolicy());
                vo.setSensitiveSource(p.getSensitiveSource());
                vo.setLowAutoEnabled(p.getLowAutoEnabled());
                vo.setMvpReviewAll(p.getMvpReviewAll());
                vo.setHighReviewRequired(p.getHighReviewRequired());
                vo.setRepairMaxRounds(p.getRepairMaxRounds());
                vo.setDialectWhitelist(SqlGuardPolicyService.whitelistFromJson(p.getDialectWhitelist()));
                vo.setConfigVersion(p.getConfigVersion());
                vo.setRemark(p.getRemark());
                vo.setUpdateTime(p.getUpdateTime());
                vos.add(vo);
            }
        }
        PageResult out = new PageResult(result.getTotal(), vos);
        return ResultUtils.success(out);
    }

    /** 接口 4 审计流水分页 */
    @Operation(summary = "审计流水分页")
    @PostMapping("/audit/page")
    public BaseResponse<PageResult> auditPage(@RequestBody SqlGuardAuditPageRequest dto) {
        if (dto.getRiskLevel() != null && !dto.getRiskLevel().isBlank()
                && com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardRiskLevel.parse(dto.getRiskLevel()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "risk_level 非法");
        }
        if (dto.getVerdict() != null && !dto.getVerdict().isBlank()
                && com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardVerdict.parse(dto.getVerdict()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "verdict 非法");
        }
        Date start = parseDate(dto.getStartDate());
        Date end = parseDate(dto.getEndDate());
        PageResult result = auditService.page(dto.getDsId(), dto.getThreadId(), dto.getRiskLevel(),
                dto.getVerdict(), dto.getGuardStatus(), start, end, dto.page(), dto.pageSize());
        List<SqlGuardAuditVO> vos = new ArrayList<>();
        for (Object o : result.getRecords()) {
            if (o instanceof SqlGuardAuditLog e) {
                SqlGuardAuditVO vo = new SqlGuardAuditVO();
                vo.setId(e.getId());
                vo.setDsId(e.getDsId());
                vo.setThreadId(e.getThreadId());
                vo.setSqlFingerprint(e.getSqlFingerprint());
                vo.setSqlAbstract(e.getSqlAbstract());
                vo.setStatementType(e.getStatementType());
                vo.setDbType(e.getDbType());
                vo.setInvolvedTables(parseStrList(e.getInvolvedTables()));
                vo.setRiskLevel(e.getRiskLevel());
                vo.setVerdict(e.getVerdict());
                vo.setBlockReason(e.getBlockReason());
                vo.setLevelL1Result(e.getLevelL1Result());
                vo.setLevelL2Result(e.getLevelL2Result());
                vo.setLevelL3Result(e.getLevelL3Result());
                vo.setLevelL4Result(e.getLevelL4Result());
                vo.setLevelL5Result(e.getLevelL5Result());
                vo.setLevelsSummary(e.getLevelsSummary());
                vo.setFixSuggestion(e.getFixSuggestion());
                vo.setCostMs(e.getCostMs());
                vo.setResultRowCount(e.getResultRowCount());
                vo.setTruncated(e.getTruncated());
                vo.setGuardStatus(e.getGuardStatus());
                vo.setCreateTime(e.getCreateTime());
                vos.add(vo);
            }
        }
        PageResult out = new PageResult(result.getTotal(), vos);
        return ResultUtils.success(out);
    }

    private Date parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try {
            return fmt.parse(text);
        } catch (ParseException e) {
            SimpleDateFormat fmt2 = new SimpleDateFormat("yyyy-MM-dd");
            try {
                return fmt2.parse(text);
            } catch (ParseException ex) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "日期格式非法，应为 yyyy-MM-dd HH:mm:ss");
            }
        }
    }

    private List<String> parseStrList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (RuntimeException ex) {
            return new ArrayList<>();
        }
    }
}
