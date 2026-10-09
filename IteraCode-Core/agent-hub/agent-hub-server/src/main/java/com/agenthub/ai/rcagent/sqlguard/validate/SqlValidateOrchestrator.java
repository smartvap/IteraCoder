package com.agenthub.ai.rcagent.sqlguard.validate;

import com.agenthub.ai.rcagent.sqlguard.adapter.L2SchemaOutcome;
import com.agenthub.ai.rcagent.sqlguard.config.SqlGuardProperties;
import com.agenthub.ai.rcagent.sqlguard.constant.FixAction;
import com.agenthub.ai.rcagent.sqlguard.constant.RuleId;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardLevelResult;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardRiskLevel;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardVerdict;
import com.agenthub.ai.rcagent.sqlguard.constant.StatementClass;
import com.agenthub.ai.rcagent.sqlguard.entity.SqlGuardPolicy;
import com.agenthub.ai.rcagent.sqlguard.exception.SqlGuardException;
import com.agenthub.ai.rcagent.sqlguard.model.FixSuggestion;
import com.agenthub.ai.rcagent.sqlguard.model.LevelResult;
import com.agenthub.ai.rcagent.sqlguard.model.RiskAssessment;
import com.agenthub.ai.rcagent.sqlguard.model.RiskRuleHit;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardRequest;
import com.agenthub.ai.rcagent.sqlguard.model.ValidationReport;
import com.agenthub.ai.rcagent.sqlguard.parser.SqlParser;
import com.agenthub.ai.rcagent.sqlguard.risk.RiskRuleContext;
import com.agenthub.ai.rcagent.sqlguard.risk.SqlRiskRuleEngine;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardPolicyService;
import com.agenthub.ai.rcagent.sqlguard.util.SqlTextUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 五级验证编排器（FR-003）。
 *
 * <p>L1 Syntax → L2 Schema → L3 Semantic → 风险分级；任一 FAIL 不进下一级并产出结构化
 * reason + fixSuggestions 供回退（最多 repair_max_rounds 轮）。L4 Execution/L5 Result 由
 * executeGuarded（SqlExecutionGuardService）补充；本编排在 explainOnly 时执行 explain 作为 L4。</p>
 */
@Slf4j
@Component
public class SqlValidateOrchestrator {

    private final SqlParser sqlParser;
    private final SqlRiskRuleEngine riskRuleEngine;
    private final L2SchemaChecker l2SchemaChecker;
    private final L3SemanticChecker l3SemanticChecker;
    private final SqlGuardPolicyService policyService;
    private final SqlGuardProperties properties;
    private final com.agenthub.ai.dbaccess.executor.ReadonlyQueryExecutor readonlyQueryExecutor;
    private final com.agenthub.ai.dbaccess.registry.DataSourceRegistry dataSourceRegistry;

    public SqlValidateOrchestrator(SqlParser sqlParser,
                                   SqlRiskRuleEngine riskRuleEngine,
                                   L2SchemaChecker l2SchemaChecker,
                                   L3SemanticChecker l3SemanticChecker,
                                   SqlGuardPolicyService policyService,
                                   SqlGuardProperties properties,
                                   com.agenthub.ai.dbaccess.executor.ReadonlyQueryExecutor readonlyQueryExecutor,
                                   com.agenthub.ai.dbaccess.registry.DataSourceRegistry dataSourceRegistry) {
        this.sqlParser = sqlParser;
        this.riskRuleEngine = riskRuleEngine;
        this.l2SchemaChecker = l2SchemaChecker;
        this.l3SemanticChecker = l3SemanticChecker;
        this.policyService = policyService;
        this.properties = properties;
        this.readonlyQueryExecutor = readonlyQueryExecutor;
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /**
     * 执行 validate（L1-L3 + 分级；explainOnly=true 时追加 L4 explain）。
     *
     * @return report；结论失败（BLOCK/L1/L2/L3 FAIL）结构化返回不抛异常，
     *         仅依赖/参数异常抛 SqlGuardException
     */
    public ValidationReport validate(SqlGuardRequest request) {
        if (request == null || request.getSql() == null || request.getSql().isBlank()) {
            throw SqlGuardException.param("SQL 不能为空");
        }
        if (request.getDsId() == null || request.getDsId().isBlank()) {
            throw SqlGuardException.param("ds_id 不能为空");
        }
        long started = System.currentTimeMillis();
        ValidationReport report = new ValidationReport();
        report.setLevels(new ArrayList<>());
        report.setFixSuggestions(new ArrayList<>());

        String dsId = request.getDsId();
        SqlGuardPolicy policy = policyService.resolveEffective(dsId);
        String dbType = resolveDbType(dsId, request.getDbType());
        int maxRows = request.getMaxRows() == null
                ? (policy.getMaxRowsDefault() == null ? 1000 : policy.getMaxRowsDefault())
                : request.getMaxRows();
        int maxSeconds = request.getMaxSeconds() == null
                ? (policy.getMaxSecondsDefault() == null ? 10 : policy.getMaxSecondsDefault())
                : request.getMaxSeconds();

        // L1 Syntax
        SqlFeature feature = sqlParser.parse(request.getSql(), dbType);
        if (StatementClass.UNKNOWN.name().equals(feature.getStatementClass())) {
            report.getLevels().add(LevelResult.builder().level("L1").result(SqlGuardLevelResult.FAIL.name())
                    .detail("SQL 语法解析失败")
                    .reason(feature.getParseWarnings().isEmpty() ? "SQL 语法错误" : feature.getParseWarnings().get(0)).build());
            report.getLevels().add(levelSkip("L2", "L1 未通过"));
            report.getLevels().add(levelSkip("L3", "L1 未通过"));
            report.getLevels().add(levelSkip("L4", "L1 未通过"));
            report.getLevels().add(levelSkip("L5", "L1 未通过"));
            report.setPassed(false);
            report.setRisk(buildSyntaxBlockRisk());
            report.getFixSuggestions().add(FixSuggestion.builder().action(FixAction.SQL_ERROR).target("sql")
                    .message("SQL 存在语法/方言错误，请修正语法后重试").build());
            fillMeta(report, request, feature, dbType, maxRows, maxSeconds, started);
            return report;
        }
        report.getLevels().add(LevelResult.builder().level("L1").result(SqlGuardLevelResult.PASS.name())
                .detail("AST 解析成功: type=" + feature.getStatementType() + " class=" + feature.getStatementClass())
                .reason("").build());

        // 写形态/SELECT * BLOCK（BR-002 直接禁止，不进 L2）
        RiskRuleContext ctx = buildRuleContext(policy, feature, null);
        if (Boolean.TRUE.equals(feature.getWriteIntent()) || Boolean.TRUE.equals(feature.getSelectStar())) {
            RiskAssessment block = riskRuleEngine.classify(feature, ctx);
            report.setRisk(block);
            report.setPassed(false);
            report.getLevels().add(levelSkip("L2", "BLOCK: " + block.getBlockedReason()));
            report.getLevels().add(levelSkip("L3", "BLOCK"));
            report.getLevels().add(levelSkip("L4", "BLOCK"));
            report.getLevels().add(levelSkip("L5", "BLOCK"));
            if (Boolean.TRUE.equals(feature.getWriteIntent())) {
                report.getFixSuggestions().add(FixSuggestion.builder().action(FixAction.SQL_ERROR).target("sql")
                        .message("仅支持只读 SELECT/EXPLAIN，DML/DDL/多语句将被 BLOCK").build());
            }
            if (Boolean.TRUE.equals(feature.getSelectStar())) {
                report.getFixSuggestions().add(FixSuggestion.builder().action(FixAction.RESELECT).target("select")
                        .message("SELECT * 禁止，请显式列出需要查询的列").build());
            }
            fillMeta(report, request, feature, dbType, maxRows, maxSeconds, started);
            return report;
        }

        // L2 Schema（经 kb 字典 → 运行时元数据降级）
        L2SchemaOutcome schema = l2SchemaChecker.check(dsId, dbType, feature, true);
        report.setWarning(null);
        if (schema == null || !Boolean.TRUE.equals(schema.getPassed())) {
            // 未知表 BLOCK（生成已不可信）；列缺失为 L2 FAIL（改字段/补 join）
            boolean hasUnknown = schema != null && schema.getUnknownTables() != null && !schema.getUnknownTables().isEmpty();
            report.setPassed(false);
            report.getLevels().add(LevelResult.builder().level("L2")
                    .result(SqlGuardLevelResult.FAIL.name())
                    .detail("Schema 校验失败: " + (schema == null ? "字典不可用" : describeSchemaFail(schema)))
                    .reason(schema == null ? "Schema 字典与运行时元数据均不可用" : describeSchemaFailReason(schema)).build());
            report.getLevels().add(levelSkip("L3", "L2 未通过"));
            report.getLevels().add(levelSkip("L4", "L2 未通过"));
            report.getLevels().add(levelSkip("L5", "L2 未通过"));
            report.setRisk(buildSchemaFailRisk(hasUnknown, schema));
            if (hasUnknown) {
                report.getFixSuggestions().add(FixSuggestion.builder().action(FixAction.FIX_TABLE)
                        .target(String.join(",", schema.getUnknownTables()))
                        .message("引用表未在 kb 字典与运行时元数据中确认，请修正表名（未知表按 BLOCK 处置）").build());
            } else {
                report.getFixSuggestions().add(FixSuggestion.builder().action(FixAction.FIX_COLUMN).target("column")
                        .message("列不存在或拼写不一致，请核对 Schema 后修正字段").build());
                if (feature.getJoinCount() != null && feature.getJoinCount() > 0) {
                    report.getFixSuggestions().add(FixSuggestion.builder().action(FixAction.FIX_JOIN).target("join")
                            .message("校验 JOIN 关联字段是否存在于两侧表").build());
                }
            }
            fillMeta(report, request, feature, dbType, maxRows, maxSeconds, started);
            return report;
        }
        report.getLevels().add(LevelResult.builder().level("L2").result(SqlGuardLevelResult.PASS.name())
                .detail("Schema 校验通过（来源=" + (schema == null ? "UNAVAILABLE" : schema.getSource()) + "）")
                .reason("").build());

        // L3 Semantic（尽力校验 WARN 不阻断）
        LevelResult l3 = l3SemanticChecker.check(feature, request.getPlan());
        report.getLevels().add(l3);
        report.setWarning(SqlGuardLevelResult.WARN.name().equals(l3.getResult()) ? l3.getDetail() : null);
        if (SqlGuardLevelResult.FAIL.name().equals(l3.getResult())) {
            report.setPassed(false);
            // 规划-生成不一致属内容问题，不应静默自动执行：非 BLOCK 风险提升到至少 MEDIUM（人工审核），
            // 避免规则引擎判定为 LOW 时"规划说筛选、SQL 无筛选"仍被自动执行（BLOCK 语义保持不动）
            report.setRisk(applyPlanMismatchGate(riskRuleEngine.classify(feature, ctx), l3));
            report.getFixSuggestions().add(FixSuggestion.builder().action(FixAction.RESELECT).target("sql")
                    .message("SQL 与规划目标不一致，请按规划重写 SQL").build());
            report.getLevels().add(levelSkip("L4", "L3 FAIL"));
            report.getLevels().add(levelSkip("L5", "L3 FAIL"));
            fillMeta(report, request, feature, dbType, maxRows, maxSeconds, started);
            return report;
        }

        // 风险分级（L2 敏感/行数估计已回填到 ctx）
        ctx = buildRuleContext(policy, feature, schema);
        RiskAssessment risk = riskRuleEngine.classify(feature, ctx);
        report.setRisk(risk);
        report.setPassed(!risk.isBlock());

        if (risk.isBlock()) {
            report.getLevels().add(levelSkip("L4", "BLOCK"));
            report.getLevels().add(levelSkip("L5", "BLOCK"));
            report.getFixSuggestions().add(FixSuggestion.builder().action(FixAction.RESELECT).target("sql")
                    .message("SQL 命中 BLOCK 规则（" + risk.getBlockedReason() + "），请重写后重试").build());
            fillMeta(report, request, feature, dbType, maxRows, maxSeconds, started);
            return report;
        }

        // L4 explainOnly（可选；不执行业务查询仅返回执行计划）
        if (Boolean.TRUE.equals(request.getExplainOnly())) {
            try {
                com.agenthub.ai.dbaccess.model.QueryRequest queryRequest =
                        com.agenthub.ai.dbaccess.model.QueryRequest.builder()
                                .dsId(dsId)
                                .sql(request.getSql())
                                .maxRows(maxRows)
                                .maxSeconds(maxSeconds)
                                .sensitivePolicy(com.agenthub.ai.dbaccess.model.SensitivePolicy.parse(
                                        policy.getSensitivePolicy() == null ? "DENY" : policy.getSensitivePolicy()))
                                .build();
                readonlyQueryExecutor.explain(queryRequest);
                report.getLevels().add(LevelResult.builder().level("L4").result(SqlGuardLevelResult.PASS.name())
                        .detail("EXPLAIN 执行计划获取成功（不返回业务数据）").reason("").build());
            } catch (com.agenthub.ai.dbaccess.exception.DbAccessException ex) {
                log.warn("sqlguard: explain 执行失败(dsId={}): {}", dsId, ex.getMessage());
                report.setPassed(false);
                report.getLevels().add(LevelResult.builder().level("L4")
                        .result(SqlGuardLevelResult.FAIL.name())
                        .detail("EXPLAIN 执行失败")
                        .reason("无法获取执行计划：" + ex.getMessage()).build());
            }
        } else {
            report.getLevels().add(levelSkip("L4", "L4 由 executeGuarded 补充执行"));
        }
        report.getLevels().add(levelSkip("L5", "L5 由 executeGuarded 执行后补充"));

        fillMeta(report, request, feature, dbType, maxRows, maxSeconds, started);
        return report;
    }

    /** 供 executeGuarded 复用：L1-L3 + 分级（不执行 L4/L5） */
    public ValidationReport preValidate(SqlGuardRequest request) {
        if (request.getExplainOnly() != null && request.getExplainOnly()) {
            request.setExplainOnly(false);
        }
        ValidationReport report = validate(request);
        return report;
    }

    private RiskRuleContext buildRuleContext(SqlGuardPolicy policy, SqlFeature feature, L2SchemaOutcome schema) {
        com.agenthub.ai.rcagent.sqlguard.config.SqlGuardProperties.Rule rule = properties.getRule();
        RiskRuleContext ctx = RiskRuleContext.builder()
                .bigJoinCount(rule == null ? 3 : Math.max(1, rule.getBigJoinCount()))
                .complexWhereCount(rule == null ? 3 : Math.max(1, rule.getComplexWhereCount()))
                .bigTableRows(rule == null ? 0 : Math.max(0, rule.getBigTableRows()))
                .blockEnabled(policy.getBlockEnabled() == null || Integer.valueOf(1).equals(policy.getBlockEnabled()))
                .selectStarBlock("BLOCK".equalsIgnoreCase(policy.getSelectStarPolicy()))
                .build();
        if (schema != null && schema.getSensitiveColumns() != null) {
            ctx.setSensitiveColumns(new ArrayList<>(schema.getSensitiveColumns()));
        }
        return ctx;
    }

    /**
     * 解析库类型：优先请求显式 dbType，否则从数据源配置读取（db-access-common）。
     */
    public String resolveDbType(String dsId, String requestDbType) {
        if (requestDbType != null && !requestDbType.isBlank()) {
            return requestDbType;
        }
        try {
            com.agenthub.ai.dbaccess.model.DsConfigView view = dataSourceRegistry.getConfig(dsId);
            return view.getDbType();
        } catch (com.agenthub.ai.dbaccess.exception.DbAccessException ex) {
            throw new SqlGuardException(com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardErrorCode.DEPENDENCY_UNAVAILABLE,
                    "数据源不可用，无法解析库类型");
        }
    }

    private void fillMeta(ValidationReport report, SqlGuardRequest request, SqlFeature feature,
                          String dbType, int maxRows, int maxSeconds, long started) {
        // report 元数据无需耗时字段；审计由上层服务完成
    }

    private LevelResult levelSkip(String level, String reason) {
        return LevelResult.builder().level(level).result(SqlGuardLevelResult.SKIP.name()).reason(reason).build();
    }

    /**
     * L3 语义 FAIL 的风险门控：把非 BLOCK 风险提升到至少 MEDIUM（转人工审核），并附一条可追溯 rulesHit。
     *
     * <p>规则引擎可能把"规划含筛选、SQL 无 WHERE"判为 LOW 并自动执行；L3 已判定规划-生成不一致时
     * 应进入人工审核而非自动放行。BLOCK 语义保持不变（本方法不动 BLOCK 结果）。</p>
     */
    private RiskAssessment applyPlanMismatchGate(RiskAssessment risk, LevelResult l3) {
        RiskAssessment result = risk == null ? new RiskAssessment() : risk;
        result.setClassifiedBy(SqlRiskRuleEngine.CLASSIFIED_BY);
        if (result.isBlock()) {
            return result;
        }
        String level = result.getRiskLevel();
        if (level == null || level.isBlank() || SqlGuardRiskLevel.LOW.name().equalsIgnoreCase(level)) {
            // 仅把 LOW/缺失提升为 MEDIUM；已是 MEDIUM/HIGH 的保持原级不降级
            result.setRiskLevel(SqlGuardRiskLevel.MEDIUM.name());
        }
        if (result.getVerdict() == null || result.getVerdict().isBlank()
                || SqlGuardVerdict.ALLOW.name().equalsIgnoreCase(result.getVerdict())) {
            result.setVerdict(SqlGuardVerdict.REVIEW.name());
        }
        String hint = "L3 语义校验不通过：" + SqlTextUtil.truncate(
                l3.getDetail() == null ? "" : l3.getDetail(), 200);
        if (result.getReviewHint() == null || result.getReviewHint().isBlank()) {
            result.setReviewHint(hint);
        }
        result.addHit(RiskRuleHit.builder()
                .ruleId(RuleId.PLAN_MISMATCH_REVIEW)
                .level(SqlGuardRiskLevel.MEDIUM.name())
                .reason(hint)
                .evidence("L3 semantic FAIL")
                .build());
        return result;
    }

    private RiskAssessment buildSyntaxBlockRisk() {
        RiskAssessment risk = new RiskAssessment();
        risk.setRiskLevel(SqlGuardRiskLevel.BLOCK.name());
        risk.setVerdict(SqlGuardVerdict.BLOCK.name());
        risk.setBlockedReason("SQL 语法解析失败，无法进入分级");
        risk.setClassifiedBy(SqlRiskRuleEngine.CLASSIFIED_BY);
        risk.setRulesHit(new ArrayList<>());
        risk.getRulesHit().add(RiskRuleHit.builder().ruleId(RuleId.SYNTAX_FAILED)
                .level(SqlGuardRiskLevel.BLOCK.name()).reason("Parser 无法解析 SQL 语法/方言").evidence("statementClass=UNKNOWN").build());
        return risk;
    }

    private RiskAssessment buildSchemaFailRisk(boolean unknown, L2SchemaOutcome schema) {
        RiskAssessment risk = new RiskAssessment();
        risk.setClassifiedBy(SqlRiskRuleEngine.CLASSIFIED_BY);
        risk.setRulesHit(new ArrayList<>());
        if (unknown) {
            risk.setRiskLevel(SqlGuardRiskLevel.BLOCK.name());
            risk.setVerdict(SqlGuardVerdict.BLOCK.name());
            risk.setBlockedReason("引用表未知（kb 字典与运行时元数据均未确认）");
            risk.getRulesHit().add(RiskRuleHit.builder().ruleId(RuleId.UNKNOWN_TABLE_BLOCK)
                    .level(SqlGuardRiskLevel.BLOCK.name()).reason("未知表生成不可信")
                    .evidence("unknownTables=" + String.join(",", schema.getUnknownTables())).build());
        } else {
            risk.setRiskLevel(SqlGuardRiskLevel.MEDIUM.name());
            risk.setVerdict(SqlGuardVerdict.REVIEW.name());
            risk.getRulesHit().add(RiskRuleHit.builder().ruleId("SCHEMA_COLUMN_FAIL")
                    .level(SqlGuardRiskLevel.MEDIUM.name()).reason("Schema 列校验未通过").evidence("L2 schema fail").build());
        }
        return risk;
    }

    private String describeSchemaFail(L2SchemaOutcome schema) {
        if (schema == null || schema.getChecks() == null) {
            return "无可用校验明细";
        }
        List<String> fails = new ArrayList<>();
        for (com.agenthub.ai.rcagent.sqlguard.model.SchemaCheckItem c : schema.getChecks()) {
            if (!Boolean.TRUE.equals(c.getExists())) {
                fails.add(c.getType() + ":" + c.getTarget());
            }
        }
        return fails.isEmpty() ? "字典不可用" : String.join(",", fails);
    }

    private String describeSchemaFailReason(L2SchemaOutcome schema) {
        if (schema == null || schema.getChecks() == null) {
            return "Schema 字典与运行时元数据均不可用（fail 倾向保守）";
        }
        for (com.agenthub.ai.rcagent.sqlguard.model.SchemaCheckItem c : schema.getChecks()) {
            if (!Boolean.TRUE.equals(c.getExists()) && c.getReason() != null) {
                return c.getReason();
            }
        }
        return "Schema 校验未通过";
    }
}
