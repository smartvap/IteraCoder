package com.agenthub.ai.rcagent.sqlguard.service;

import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.rcagent.sqlguard.adapter.L2SchemaOutcome;
import com.agenthub.ai.rcagent.sqlguard.config.SqlGuardProperties;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardLevelResult;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardRiskLevel;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardVerdict;
import com.agenthub.ai.rcagent.sqlguard.entity.SqlGuardAuditLog;
import com.agenthub.ai.rcagent.sqlguard.model.FixSuggestion;
import com.agenthub.ai.rcagent.sqlguard.model.LevelResult;
import com.agenthub.ai.rcagent.sqlguard.model.RiskAssessment;
import com.agenthub.ai.rcagent.sqlguard.model.SchemaCheckItem;
import com.agenthub.ai.rcagent.sqlguard.model.SchemaCheckResult;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardRequest;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardValidateOutcome;
import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import com.agenthub.ai.rcagent.sqlguard.model.SqlSchemaCheckRequest;
import com.agenthub.ai.rcagent.sqlguard.model.ValidationReport;
import com.agenthub.ai.rcagent.sqlguard.parser.SqlParser;
import com.agenthub.ai.rcagent.sqlguard.risk.RiskRuleContext;
import com.agenthub.ai.rcagent.sqlguard.risk.SqlRiskRuleEngine;
import com.agenthub.ai.rcagent.sqlguard.util.SqlTextUtil;
import com.agenthub.ai.rcagent.sqlguard.validate.L2SchemaChecker;
import com.agenthub.ai.rcagent.sqlguard.validate.SqlValidateOrchestrator;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * SQL 防护 SPI 门面（S-1/S-2/S-3/S-4）：parse/validate/classify/schemaCheck。
 *
 * <p>结论失败（BLOCK/FAIL）作为结构化结果返回不抛异常（BR-004/FR-005 回退循环关键）；
 * 仅参数非法/依赖模块不可用抛 SqlGuardException。每次 validate 追加审计流水（只存指纹+抽象，无原文）。</p>
 */
@Slf4j
@Service
public class SqlGuardService {

    private final SqlParser sqlParser;
    private final SqlRiskRuleEngine riskRuleEngine;
    private final SqlValidateOrchestrator orchestrator;
    private final L2SchemaChecker l2SchemaChecker;
    private final SqlGuardAuditService auditService;
    private final SqlGuardPolicyService policyService;
    private final SqlGuardProperties properties;

    public SqlGuardService(SqlParser sqlParser,
                           SqlRiskRuleEngine riskRuleEngine,
                           SqlValidateOrchestrator orchestrator,
                           L2SchemaChecker l2SchemaChecker,
                           SqlGuardAuditService auditService,
                           SqlGuardPolicyService policyService,
                           SqlGuardProperties properties) {
        this.sqlParser = sqlParser;
        this.riskRuleEngine = riskRuleEngine;
        this.orchestrator = orchestrator;
        this.l2SchemaChecker = l2SchemaChecker;
        this.auditService = auditService;
        this.policyService = policyService;
        this.properties = properties;
    }

    /** S-1 AST 解析与特征提取 */
    public SqlFeature parse(String sql, String dbType) {
        return sqlParser.parse(sql, dbType);
    }

    /** S-3 纯分级引擎（默认安全阈值，供单独分级调用方） */
    public RiskAssessment classify(SqlFeature feature) {
        return riskRuleEngine.classify(feature, RiskRuleContext.defaults());
    }

    /** S-2 五级验证 + 风险分级主入口（含审计落库） */
    public ValidationReport validate(SqlGuardRequest request) {
        return validateOutcome(request).getReport();
    }

    /** S-2 变体：五级验证 + 风险分级，并返回 report 与本次审计流水 ID（HTTP 接口 1 契约 data.auditId） */
    public SqlGuardValidateOutcome validateOutcome(SqlGuardRequest request) {
        if (request.getOperatorId() == null) {
            request.setOperatorId(BaseContext.getCurrentId());
        }
        long started = System.currentTimeMillis();
        ValidationReport report = orchestrator.validate(request);
        if (report == null) {
            report = new ValidationReport();
            report.setPassed(false);
        }
        // 审计入口解析真实库类型：Http 契约 dbType 可空（缺省按 ds_id resolve），此处解析后回填，
        // 保证 audit 落库 db_type 与二次 parse 使用真实方言，禁止硬编码 MYSQL（与 executeGuarded 语义一致）
        if (request.getDbType() == null || request.getDbType().isBlank()) {
            request.setDbType(resolveEffectiveDbType(request.getDsId()));
        }
        Long auditId = audit(request, report, "VALIDATED", System.currentTimeMillis() - started, null, null);
        return SqlGuardValidateOutcome.builder().report(report).auditId(auditId).build();
    }

    /** S-4 Schema 预检（仅 L2） */
    public SchemaCheckResult schemaCheck(SqlSchemaCheckRequest request) {
        if (request.getDsId() == null || request.getDsId().isBlank()) {
            throw com.agenthub.ai.rcagent.sqlguard.exception.SqlGuardException.param("ds_id 不能为空");
        }
        if (request.getSql() == null || request.getSql().isBlank()) {
            throw com.agenthub.ai.rcagent.sqlguard.exception.SqlGuardException.param("SQL 不能为空");
        }
        String dbType = orchestrator.resolveDbType(request.getDsId(), request.getDbType());
        SqlFeature feature = sqlParser.parse(request.getSql(), dbType);
        L2SchemaOutcome outcome = l2SchemaChecker.check(request.getDsId(), dbType, feature, request.includeRelations());
        SchemaCheckResult result = new SchemaCheckResult();
        result.setPassed(outcome.getPassed());
        result.setSource(outcome.getSource());
        List<SchemaCheckItem> items = new ArrayList<>();
        if (outcome.getChecks() != null) {
            items.addAll(outcome.getChecks());
        }
        // 未知表补充标记
        if (outcome.getUnknownTables() != null && !outcome.getUnknownTables().isEmpty()) {
            for (String t : outcome.getUnknownTables()) {
                boolean added = items.stream().anyMatch(i -> "TABLE".equals(i.getType()) && t.equals(i.getTarget()));
                if (!added) {
                    items.add(SchemaCheckItem.builder().type("TABLE").target(t).exists(false).typeMatch(false)
                            .evidence("unknown").reason("表未在字典/元数据中确认").build());
                }
            }
        }
        result.setChecks(items);
        return result;
    }

    /**
     * 审计落库（只存指纹/抽象/风险结论，不落 SQL 原文与敏感字面量）。
     *
     * @return 落库后的审计主键（record 返回 AUTO 主键；失败返回 null）
     */
    private Long audit(SqlGuardRequest request, ValidationReport report, String guardStatus,
                       long costMs, Integer rowCount, Integer truncated) {
        // 审计使用真实库类型（validateOutcome 已按 ds 解析回填；此处防御兜底，禁止硬编码 MYSQL）
        String dbType = request.getDbType();
        if (dbType == null || dbType.isBlank()) {
            dbType = resolveEffectiveDbType(request.getDsId());
        }
        SqlGuardAuditLog entity = new SqlGuardAuditLog();
        entity.setDsId(request.getDsId());
        // 回填工作流会话 thread_id，使校验路径审计可按会话串联（缺省 null 时保持不落）
        entity.setThreadId(request.getThreadId());
        entity.setSqlFingerprint(SqlTextUtil.fingerprint(request.getSql()));
        entity.setSqlAbstract(SqlTextUtil.abstractSql(request.getSql()));
        entity.setDbType(dbType);
        entity.setCostMs(costMs);
        entity.setGuardStatus(guardStatus);
        entity.setResultRowCount(rowCount);
        entity.setTruncated(truncated);
        entity.setRiskLevel(report.getRisk() == null ? SqlGuardRiskLevel.LOW.name() : report.getRisk().getRiskLevel());
        entity.setVerdict(report.getRisk() == null ? SqlGuardVerdict.REVIEW.name() : report.getRisk().getVerdict());
        entity.setBlockReason(report.getRisk() == null ? null : report.getRisk().getBlockedReason());
        entity.setLevelsSummary(summaryJson(report.getLevels()));
        fillLevelResults(entity, report);
        List<String> fixes = new ArrayList<>();
        if (report.getFixSuggestions() != null) {
            for (FixSuggestion f : report.getFixSuggestions()) {
                fixes.add(JSON.toJSONString(f));
            }
        }
        entity.setFixSuggestion(fixes.isEmpty() ? null : JSON.toJSONString(fixes));
        // statement_type/涉及表（仅表名摘要，无数据）
        try {
            SqlFeature feature = sqlParser.parse(request.getSql(), dbType);
            entity.setStatementType(feature.getStatementType() == null ? "UNKNOWN" : feature.getStatementType());
            entity.setInvolvedTables(JSON.toJSONString(feature.tableNames()));
        } catch (RuntimeException ex) {
            entity.setStatementType("UNKNOWN");
            entity.setInvolvedTables(null);
        }
        return auditService.record(entity);
    }

    /**
     * 按 ds_id 解析真实库类型（Http 契约 dbType 可空时缺省解析，与 executeGuarded 一致）。
     * 数据源不可用由 orchestrator.resolveDbType 保持 43007 语义；解析结果缺失视为参数异常，禁止 MYSQL 兜底。
     */
    private String resolveEffectiveDbType(String dsId) {
        String dbType = orchestrator.resolveDbType(dsId, null);
        if (dbType == null || dbType.isBlank()) {
            throw com.agenthub.ai.rcagent.sqlguard.exception.SqlGuardException.param("无法解析数据源库类型");
        }
        return dbType;
    }

    /** 将报告 levels 映射到审计 level_l1~l5_result 字段 */
    private void fillLevelResults(SqlGuardAuditLog entity, ValidationReport report) {
        if (report.getLevels() == null) {
            return;
        }
        for (LevelResult l : report.getLevels()) {
            if (l == null || l.getLevel() == null || l.getResult() == null) {
                continue;
            }
            switch (l.getLevel()) {
                case "L1" -> entity.setLevelL1Result(l.getResult());
                case "L2" -> entity.setLevelL2Result(l.getResult());
                case "L3" -> entity.setLevelL3Result(l.getResult());
                case "L4" -> entity.setLevelL4Result(l.getResult());
                case "L5" -> entity.setLevelL5Result(l.getResult());
                default -> {
                }
            }
        }
    }

    private String summaryJson(List<LevelResult> levels) {
        List<com.alibaba.fastjson2.JSONObject> arr = new ArrayList<>();
        if (levels != null) {
            for (LevelResult l : levels) {
                com.alibaba.fastjson2.JSONObject o = new com.alibaba.fastjson2.JSONObject();
                o.put("level", l.getLevel());
                o.put("result", l.getResult());
                if (l.getReason() != null) {
                    o.put("reason", SqlTextUtil.truncate(l.getReason(), 200));
                }
                arr.add(o);
            }
        }
        return arr.isEmpty() ? null : JSON.toJSONString(arr);
    }

    /** report 供 debug/validate VO 返回 */
    public SqlGuardProperties getProperties() {
        return properties;
    }
}
