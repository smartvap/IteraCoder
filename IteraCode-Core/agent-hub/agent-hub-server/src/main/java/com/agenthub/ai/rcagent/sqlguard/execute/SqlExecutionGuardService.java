package com.agenthub.ai.rcagent.sqlguard.execute;

import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.dbaccess.config.DbAccessProperties;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.QueryRequest;
import com.agenthub.ai.dbaccess.model.QueryResult;
import com.agenthub.ai.dbaccess.executor.ReadonlyQueryExecutor;
import com.agenthub.ai.dbaccess.model.ResourceLimits;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.rcagent.sqlguard.constant.GuardStatus;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardErrorCode;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardLevelResult;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardVerdict;
import com.agenthub.ai.rcagent.sqlguard.entity.SqlGuardPolicy;
import com.agenthub.ai.rcagent.sqlguard.entity.SqlGuardAuditLog;
import com.agenthub.ai.rcagent.sqlguard.exception.SqlGuardException;
import com.agenthub.ai.rcagent.sqlguard.model.FixSuggestion;
import com.agenthub.ai.rcagent.sqlguard.model.GuardedQueryResult;
import com.agenthub.ai.rcagent.sqlguard.model.LevelResult;
import com.agenthub.ai.rcagent.sqlguard.model.RiskAssessment;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardQuery;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardRequest;
import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import com.agenthub.ai.rcagent.sqlguard.model.ValidationReport;
import com.agenthub.ai.rcagent.sqlguard.parser.SqlParser;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardAuditService;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardPolicyService;
import com.agenthub.ai.rcagent.sqlguard.util.SqlTextUtil;
import com.agenthub.ai.rcagent.sqlguard.validate.L5ResultChecker;
import com.agenthub.ai.rcagent.sqlguard.validate.SqlValidateOrchestrator;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 分级保护只读执行（FR-004/BR-005，SPI S-5 executeGuarded）。
 *
 * <p>先 validate 前置（L1-L3 + 分级）再按 verdict 分支：
 * BLOCK 不执行返回 executed=false；LOW 且 low_auto_enabled=1 且 !mvp_review_all 自动执行；
 * MED/HIGH 需 requireManualApproval=true 才执行；敏感字段 DENY 由 db-access-common 执行层拒绝
 * （本模块捕获后结构化 REJECTED 返回，43003 语义）；连接/超时等 DbAccessException 包装 SqlGuardException。
 * L5 Result 检查执行后结果合理性。</p>
 */
@Slf4j
@Service
public class SqlExecutionGuardService {

    private final SqlValidateOrchestrator orchestrator;
    private final ReadonlyQueryExecutor readonlyQueryExecutor;
    private final SqlGuardPolicyService policyService;
    private final SqlGuardAuditService auditService;
    private final L5ResultChecker l5ResultChecker;
    private final SqlParser sqlParser;
    private final DataSourceRegistry dataSourceRegistry;
    private final DbAccessProperties dbAccessProperties;

    public SqlExecutionGuardService(SqlValidateOrchestrator orchestrator,
                                    ReadonlyQueryExecutor readonlyQueryExecutor,
                                    SqlGuardPolicyService policyService,
                                    SqlGuardAuditService auditService,
                                    L5ResultChecker l5ResultChecker,
                                    SqlParser sqlParser,
                                    DataSourceRegistry dataSourceRegistry,
                                    DbAccessProperties dbAccessProperties) {
        this.orchestrator = orchestrator;
        this.readonlyQueryExecutor = readonlyQueryExecutor;
        this.policyService = policyService;
        this.auditService = auditService;
        this.l5ResultChecker = l5ResultChecker;
        this.sqlParser = sqlParser;
        this.dataSourceRegistry = dataSourceRegistry;
        this.dbAccessProperties = dbAccessProperties;
    }

    /**
     * 受保护只读执行（S-5）。
     */
    public GuardedQueryResult executeGuarded(SqlGuardQuery query) {
        if (query == null) {
            throw SqlGuardException.param("执行请求不能为空");
        }
        if (query.getDsId() == null || query.getDsId().isBlank()) {
            throw SqlGuardException.param("ds_id 不能为空");
        }
        if (query.getSql() == null || query.getSql().isBlank()) {
            throw SqlGuardException.param("SQL 不能为空");
        }
        if (query.getOperatorId() == null) {
            query.setOperatorId(BaseContext.getCurrentId());
        }
        long started = System.currentTimeMillis();
        SqlGuardPolicy policy = policyService.resolveEffective(query.getDsId());
        // 前置解析真实库类型（按 ds_id resolve，禁止硬编码 MYSQL）：贯穿 guardRequest/审计/L5 特征解析
        String dbType = resolveEffectiveDbType(query.getDsId());

        // 1) validate 前置（L1-L3 + 分级，不执行业务查询）
        SqlGuardRequest guardRequest = SqlGuardRequest.builder()
                .dsId(query.getDsId())
                .sql(query.getSql())
                .dbType(dbType)
                .plan(query.getPlan())
                .maxRows(query.getMaxRows())
                .maxSeconds(query.getMaxSeconds())
                .explainOnly(false)
                .operatorId(query.getOperatorId())
                .threadId(query.getThreadId())
                .build();
        ValidationReport report = orchestrator.validate(guardRequest);
        RiskAssessment risk = report.getRisk() == null ? new RiskAssessment() : report.getRisk();
        GuardedQueryResult guarded = new GuardedQueryResult();
        guarded.setRisk(risk);
        guarded.setReport(report);
        guarded.setExecuted(false);

        // 2) BLOCK：不执行
        if (SqlGuardVerdict.BLOCK.name().equals(risk.getVerdict())) {
            report.getLevels().add(level("L4", SqlGuardLevelResult.SKIP.name(), "BLOCK 不执行"));
            report.getLevels().add(level("L5", SqlGuardLevelResult.SKIP.name(), "BLOCK 不执行"));
            report.setPassed(false);
            audit(query, report, risk, GuardStatus.REJECTED.name(), System.currentTimeMillis() - started, null, null, dbType);
            return guarded;
        }

        // 3) LOW：low_auto_enabled=1 且 !mvp_review_all 自动；否则转人工
        boolean requireApproval = Boolean.TRUE.equals(query.getRequireManualApproval());
        boolean lowAuto = !SqlGuardVerdict.ALLOW.name().equals(risk.getVerdict())
                || (Integer.valueOf(1).equals(policy.getLowAutoEnabled())
                && !Integer.valueOf(1).equals(policy.getMvpReviewAll()));
        boolean allowExecute;
        if (SqlGuardVerdict.ALLOW.name().equals(risk.getVerdict())) {
            allowExecute = Integer.valueOf(1).equals(policy.getLowAutoEnabled())
                    && !Integer.valueOf(1).equals(policy.getMvpReviewAll());
        } else {
            // MED/HIGH：requireManualApproval=true 放行（HIGH 强制人工由调用方审核通过标记）
            allowExecute = requireApproval;
            if (SqlGuardVerdict.REVIEW.name().equals(risk.getVerdict()) && !requireApproval) {
                report.getLevels().add(level("L4", SqlGuardLevelResult.SKIP.name(),
                        "MED/HIGH 需人工审核放行（requireManualApproval=false）"));
                report.getLevels().add(level("L5", SqlGuardLevelResult.SKIP.name(), "未执行"));
                report.setPassed(true);
                audit(query, report, risk, GuardStatus.REJECTED.name(), System.currentTimeMillis() - started, null, null, dbType);
                return guarded;
            }
        }
        if (!allowExecute) {
            report.getLevels().add(level("L4", SqlGuardLevelResult.SKIP.name(), "策略要求人工审核，未放行执行"));
            report.getLevels().add(level("L5", SqlGuardLevelResult.SKIP.name(), "未执行"));
            report.setPassed(true);
            audit(query, report, risk, GuardStatus.REJECTED.name(), System.currentTimeMillis() - started, null, null, dbType);
            return guarded;
        }

        // 4) 只读执行（db-access-common ReadonlyQueryExecutor，maxRows/maxSeconds/sensitivePolicy 传入）
        //    maxRows/maxSeconds 取「请求值 ∩ ds limits ∩ 模块上限」最严格者（BR-005）：
        //    db-access-common 对超过模块上限的参数请求直接抛 PARAM_INVALID（而非交集截断），
        //    故本模块执行前先将请求 clamp 到 ds limits（DataSourceRegistry.resolve 已叠加模块默认上限），
        //    避免 >模块上限 的合法请求被误包装为 43007「依赖服务不可用」。
        LimitContext limits = resolveLimits(query, policy);
        int maxRows = limits.maxRows;
        int maxSeconds = limits.maxSeconds;
        String sensitivePolicyText = policy.getSensitivePolicy() == null ? "DENY" : policy.getSensitivePolicy();
        QueryRequest execRequest = QueryRequest.builder()
                .dsId(query.getDsId())
                .sql(query.getSql())
                .maxRows(maxRows)
                .maxSeconds(maxSeconds)
                .sensitivePolicy(com.agenthub.ai.dbaccess.model.SensitivePolicy.parse(sensitivePolicyText))
                .build();
        try {
            QueryResult result = readonlyQueryExecutor.execute(execRequest);
            guarded.setExecuted(true);
            guarded.setResult(result);
            report.getLevels().add(level("L4", SqlGuardLevelResult.PASS.name(),
                    "只读执行成功 rows=" + result.getRowCount() + (Boolean.TRUE.equals(result.getTruncated()) ? "(截断)" : "")));

            // 5) L5 Result 检查：FAIL 时置 passed=false 驱动 workflow 回退（BR-004/L5 异常回退）
            if (!Boolean.TRUE.equals(query.getSkipResultCheck())) {
                SqlFeature feature = parseFeature(query.getSql(), dbType);
                LevelResult l5 = l5ResultChecker.check(feature, query.getPlan(), result);
                report.getLevels().add(l5);
                boolean l5Failed = SqlGuardLevelResult.FAIL.name().equals(l5.getResult());
                if (SqlGuardLevelResult.WARN.name().equals(l5.getResult()) || l5Failed) {
                    List<FixSuggestion> sugg = l5ResultChecker.suggestions(feature, query.getPlan(), result);
                    if (sugg != null) {
                        for (FixSuggestion s : sugg) {
                            report.getFixSuggestions().add(s);
                        }
                    }
                }
                report.setPassed(!l5Failed);
            } else {
                report.getLevels().add(level("L5", SqlGuardLevelResult.SKIP.name(), "skipResultCheck=true"));
                report.setPassed(true);
            }
            guarded.setReport(report);
            audit(query, report, risk, GuardStatus.EXECUTED.name(), System.currentTimeMillis() - started,
                    result.getRowCount(), Boolean.TRUE.equals(result.getTruncated()) ? 1 : 0, dbType);
            return guarded;
        } catch (DbAccessException ex) {
            // 敏感拒绝 → 结构化 REJECTED（43003 语义不抛）；超时 → TIMEOUT 结构化；
            // 参数非法（maxRows/maxSeconds 超模块上限等）→ 参数异常（避免误报 43007）；其他 → 包装 SqlGuardException(43007)
            if (ex.getCode() == DbAccessErrorCode.PARAM_INVALID.getCode()) {
                throw SqlGuardException.param("只读执行参数非法: " + ex.getMessage());
            }
            if (ex.getCode() == DbAccessErrorCode.SENSITIVE_DENIED.getCode()) {
                report.getLevels().add(level("L4", SqlGuardLevelResult.FAIL.name(), "命中敏感字段，访问被拒绝"));
                report.getLevels().add(level("L5", SqlGuardLevelResult.SKIP.name(), "未执行"));
                report.setPassed(false);
                report.getFixSuggestions().add(FixSuggestion.builder().action("RESELECT").target("sensitive")
                        .message("查询命中 HIGH 敏感字段，请移除敏感列或申请脱敏授权").build());
                guarded.setReport(report);
                audit(query, report, risk, GuardStatus.REJECTED.name(), System.currentTimeMillis() - started, null, null, dbType);
                return guarded;
            }
            if (ex.getCode() == DbAccessErrorCode.TIMEOUT.getCode()) {
                report.getLevels().add(level("L4", SqlGuardLevelResult.FAIL.name(), "查询执行超时"));
                report.getLevels().add(level("L5", SqlGuardLevelResult.SKIP.name(), "超时终止"));
                report.setPassed(false);
                guarded.setReport(report);
                audit(query, report, risk, GuardStatus.TIMEOUT.name(), System.currentTimeMillis() - started, null, null, dbType);
                return guarded;
            }
            // 只读层拒绝（SQL_REJECTED/SYSTEM_ERROR/CONNECTION_FAILED 等）：先落审计再抛；
            // 否则执行失败时刻审计不可追溯（只留 validate 阶段 VALIDATED 一条）。
            // 复用 GuardStatus.REJECTED（语义含「只读层拒绝」），不新增枚举值；
            // detail 只含异常 message 摘要，不落 SQL 原文/敏感字面量。
            report.getLevels().add(level("L4", SqlGuardLevelResult.FAIL.name(),
                    "只读执行失败: " + ex.getMessage()));
            report.getLevels().add(level("L5", SqlGuardLevelResult.SKIP.name(), "未执行"));
            report.setPassed(false);
            guarded.setReport(report);
            audit(query, report, risk, GuardStatus.REJECTED.name(), System.currentTimeMillis() - started, null, null, dbType);
            throw new SqlGuardException(SqlGuardErrorCode.DEPENDENCY_UNAVAILABLE,
                    "只读执行依赖服务不可用: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            if (ex instanceof SqlGuardException) {
                throw ex;
            }
            // 未预期运行时异常同样先落审计（REJECTED + L4 FAIL）再按原逻辑包装抛出，
            // 保证任何执行失败都有审计流水可追溯（审计落库失败不改变主流程）。
            report.getLevels().add(level("L4", SqlGuardLevelResult.FAIL.name(),
                    "只读执行失败: " + ex.getMessage()));
            report.getLevels().add(level("L5", SqlGuardLevelResult.SKIP.name(), "未执行"));
            report.setPassed(false);
            guarded.setReport(report);
            audit(query, report, risk, GuardStatus.REJECTED.name(), System.currentTimeMillis() - started, null, null, dbType);
            throw new SqlGuardException(SqlGuardErrorCode.DEPENDENCY_UNAVAILABLE,
                    "只读执行失败: " + ex.getMessage(), ex);
        }
    }

    private SqlFeature parseFeature(String sql, String dbType) {
        try {
            return sqlParser.parse(sql, dbType);
        } catch (RuntimeException ex) {
            SqlFeature f = new SqlFeature();
            f.setJoinCount(0);
            f.setWhereCount(0);
            return f;
        }
    }

    private void audit(SqlGuardQuery query, ValidationReport report, RiskAssessment risk,
                       String guardStatus, long costMs, Integer rowCount, Integer truncated, String dbType) {
        SqlGuardAuditLog entity = new SqlGuardAuditLog();
        entity.setDsId(query.getDsId());
        // 回填工作流会话 thread_id，使审计可按会话串联（缺省 null 时保持不落）
        entity.setThreadId(query.getThreadId());
        entity.setSqlFingerprint(SqlTextUtil.fingerprint(query.getSql()));
        entity.setSqlAbstract(SqlTextUtil.abstractSql(query.getSql()));
        entity.setDbType(dbType);
        entity.setCostMs(costMs);
        entity.setGuardStatus(guardStatus);
        entity.setResultRowCount(rowCount);
        entity.setTruncated(truncated);
        entity.setRiskLevel(risk.getRiskLevel() == null ? "LOW" : risk.getRiskLevel());
        entity.setVerdict(risk.getVerdict() == null ? "REVIEW" : risk.getVerdict());
        entity.setBlockReason(risk.getBlockedReason());
        if (report.getLevels() != null) {
            for (LevelResult l : report.getLevels()) {
                if ("L1".equals(l.getLevel())) {
                    entity.setLevelL1Result(l.getResult());
                } else if ("L2".equals(l.getLevel())) {
                    entity.setLevelL2Result(l.getResult());
                } else if ("L3".equals(l.getLevel())) {
                    entity.setLevelL3Result(l.getResult());
                } else if ("L4".equals(l.getLevel())) {
                    entity.setLevelL4Result(l.getResult());
                } else if ("L5".equals(l.getLevel())) {
                    entity.setLevelL5Result(l.getResult());
                }
            }
        }
        entity.setLevelsSummary(summary(report.getLevels()));
        if (report.getFixSuggestions() != null && !report.getFixSuggestions().isEmpty()) {
            List<String> arr = new ArrayList<>();
            for (FixSuggestion f : report.getFixSuggestions()) {
                arr.add(JSON.toJSONString(f));
            }
            entity.setFixSuggestion(JSON.toJSONString(arr));
        }
        try {
            SqlFeature f = sqlParser.parse(query.getSql(), dbType);
            entity.setStatementType(f.getStatementType() == null ? "UNKNOWN" : f.getStatementType());
            entity.setInvolvedTables(JSON.toJSONString(f.tableNames()));
        } catch (RuntimeException ignored) {
            entity.setStatementType("UNKNOWN");
        }
        Date now = new Date();
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setIsDeleted(0);
        auditService.record(entity);
    }

    /**
     * 按 ds_id 解析真实库类型（与 orchestrator.resolveDbType 一致），审计与 L5 特征解析共用。
     * 数据源不可用时抛出与编排器一致的 SqlGuardException(43007)；解析结果缺失视为参数异常。
     */
    private String resolveEffectiveDbType(String dsId) {
        String dbType = orchestrator.resolveDbType(dsId, null);
        if (dbType == null || dbType.isBlank()) {
            throw SqlGuardException.param("无法解析数据源库类型");
        }
        return dbType;
    }

    /**
     * maxRows/maxSeconds 限制交集（BR-005）：
     * 请求值 ∩ ds limits（DataSourceRegistry.resolve 已叠加 db-access-common 模块默认上限）。
     * 策略默认值仅作「未显式请求」时的候选，最终 clamp 到 ds 上界，避免被 db-access 层 PARAM_INVALID 拒绝。
     */
    private LimitContext resolveLimits(SqlGuardQuery query, SqlGuardPolicy policy) {
        int moduleMaxRows = 1000;
        int moduleMaxSeconds = 10;
        int dsMaxRows = moduleMaxRows;
        int dsMaxSeconds = moduleMaxSeconds;
        try {
            ResourceLimits defaultLimits = dbAccessProperties.defaultLimitsModel();
            if (defaultLimits != null) {
                if (defaultLimits.getMaxRows() != null) {
                    moduleMaxRows = defaultLimits.getMaxRows();
                }
                if (defaultLimits.getMaxSeconds() != null) {
                    moduleMaxSeconds = defaultLimits.getMaxSeconds();
                }
            }
            DataSourceContext dsContext = dataSourceRegistry.resolve(query.getDsId());
            if (dsContext != null && dsContext.getLimits() != null) {
                ResourceLimits limits = dsContext.getLimits();
                if (limits.getMaxRows() != null) {
                    dsMaxRows = Math.min(moduleMaxRows, limits.getMaxRows());
                }
                if (limits.getMaxSeconds() != null) {
                    dsMaxSeconds = Math.min(moduleMaxSeconds, limits.getMaxSeconds());
                }
            }
        } catch (RuntimeException ex) {
            // 数据源解析失败：回退到模块默认上限继续（真正的连接失败由执行器阶段结构化处理）
            dsMaxRows = moduleMaxRows;
            dsMaxSeconds = moduleMaxSeconds;
        }
        int reqRows = query.getMaxRows() == null
                ? (policy.getMaxRowsDefault() == null ? moduleMaxRows : policy.getMaxRowsDefault())
                : query.getMaxRows();
        int reqSeconds = query.getMaxSeconds() == null
                ? (policy.getMaxSecondsDefault() == null ? moduleMaxSeconds : policy.getMaxSecondsDefault())
                : query.getMaxSeconds();
        int maxRows = Math.max(1, Math.min(reqRows, dsMaxRows));
        int maxSeconds = Math.max(1, Math.min(reqSeconds, dsMaxSeconds));
        return new LimitContext(maxRows, maxSeconds);
    }

    /** 限制上下文（避免魔法值四处散落） */
    private static class LimitContext {
        private final int maxRows;
        private final int maxSeconds;

        private LimitContext(int maxRows, int maxSeconds) {
            this.maxRows = maxRows;
            this.maxSeconds = maxSeconds;
        }
    }

    private String summary(List<LevelResult> levels) {
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        List<com.alibaba.fastjson2.JSONObject> arr = new ArrayList<>();
        for (LevelResult l : levels) {
            com.alibaba.fastjson2.JSONObject o = new com.alibaba.fastjson2.JSONObject();
            o.put("level", l.getLevel());
            o.put("result", l.getResult());
            if (l.getReason() != null) {
                o.put("reason", SqlTextUtil.truncate(l.getReason(), 200));
            }
            arr.add(o);
        }
        return JSON.toJSONString(arr);
    }

    private LevelResult level(String level, String result, String detail) {
        return LevelResult.builder().level(level).result(result).detail(detail).reason("").build();
    }
}
