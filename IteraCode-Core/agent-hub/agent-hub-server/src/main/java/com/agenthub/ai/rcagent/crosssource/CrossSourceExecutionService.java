package com.agenthub.ai.rcagent.crosssource;

import com.agenthub.ai.dbaccess.model.QueryResult;
import com.agenthub.ai.rcagent.crosssource.InMemoryHashJoiner.JoinOutcome;
import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceQueryResult;
import com.agenthub.ai.rcagent.crosssource.model.PushdownPlan;
import com.agenthub.ai.rcagent.crosssource.model.SourceQueryResult;
import com.agenthub.ai.rcagent.crosssource.model.SourceQuerySql;
import com.agenthub.ai.rcagent.sqlguard.model.GuardedQueryResult;
import com.agenthub.ai.rcagent.sqlguard.model.LevelResult;
import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import com.agenthub.ai.rcagent.sqlguard.model.ValidationReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 跨源执行编排服务（module-003，{@code @Service}，**无状态 / 线程安全**）。
 *
 * <p>编排流程（api-contract §4.7 / §4.5.1 E3~E8）：探测侧（PROBE，默认左源）先校验再执行
 * （含 join 前过滤下推）→ 取其 join key 集合 → {@link SemiJoinPushdownPlanner} 规划下推 →
 * 目标侧（TARGET）逐片「**先校验再执行**」（任一片 BLOCK → 整体失败且**不产出任何结果**）→
 * 多片按 key 去重合并 → {@link InMemoryHashJoiner} 内存 join → {@link CrossSourceResultMerger} 合并。</p>
 *
 * <p>失败契约：{@link #executeAndJoin} **不抛异常**，失败信息置于逐源摘要
 * （{@code execState} / {@code failureReason}，脱敏且含「源 N」序号），{@code merged} 返回 {@code null}；
 * {@link #validatePerSource} **不吞异常**（fail-closed 上抛，与既有 {@code RcaValidateNode} 一致）。</p>
 *
 * <p><b>安全</b>：日志仅记长度/计数/源序号/阶段；禁止打印 SQL 全文、数据行、dsId 与库名组合、任何凭据；
 * 下推字面量仅来自白名单渲染结果，逐片执行前一律再经 sqlguard 复验。</p>
 */
@Slf4j
@Service
public class CrossSourceExecutionService {

    /** 单源行数上限兜底（配置缺失时使用） */
    private static final int DEFAULT_ROW_LIMIT = 2000;
    /**
     * 未执行源的诊断态（**内部逐源摘要专用**，体现「已拦截 / 未执行」语义，
     * 不得复用 {@code FAILED} 冒充「该源执行失败」；失败路径不写 {@code SOURCE_QUERY_RESULTS}，
     * 故该值不进入 State / SSE，仅用于节点定位真实失败源）。
     */
    private static final String STATE_NOT_EXECUTED = "NOT_EXECUTED";
    /** 未执行说明前缀（脱敏，仅含源序号） */
    private static final String REASON_NOT_EXECUTED_PREFIX = "未执行（源 ";
    /** 未执行说明后缀：失败源被安全体系拦截 */
    private static final String REASON_NOT_EXECUTED_BLOCKED_SUFFIX = " 已拦截）";
    /** 未执行说明后缀：失败源执行失败 / 超时 */
    private static final String REASON_NOT_EXECUTED_SUFFIX = " 已失败）";
    /** 异常摘要截断长度（脱敏） */
    private static final int ERROR_MESSAGE_MAX_LEN = 200;

    private final CrossSourceSqlGuardAdapter guardAdapter;
    private final CrossSourcePlanViewAdapter planViewAdapter;
    private final SemiJoinPushdownPlanner pushdownPlanner;
    private final InMemoryHashJoiner joiner;
    private final CrossSourceResultMerger merger;
    private final CrossSourceProperties properties;

    public CrossSourceExecutionService(CrossSourceSqlGuardAdapter guardAdapter,
            CrossSourcePlanViewAdapter planViewAdapter,
            SemiJoinPushdownPlanner pushdownPlanner,
            InMemoryHashJoiner joiner,
            CrossSourceResultMerger merger,
            CrossSourceProperties properties) {
        this.guardAdapter = guardAdapter;
        this.planViewAdapter = planViewAdapter;
        this.pushdownPlanner = pushdownPlanner;
        this.joiner = joiner;
        this.merger = merger;
        this.properties = properties;
    }

    /**
     * 逐源只读校验（api-contract §4.7）。
     *
     * <p>{@code plan} 经 {@link CrossSourcePlanViewAdapter} 转为 {@link SqlPlanView}
     * （映射失败则 {@code plan=null}，该源 L3 SKIP）；**不吞异常**（服务缺失/调用异常一律上抛，
     * 由调用方 fail-closed 终止）。</p>
     *
     * @param sqls     逐源 SQL（顺序与返回报告一致）
     * @param plan     join 计划（可空）
     * @param threadId 会话线程 ID（审计可追溯）
     * @return 逐源验证报告（SQL 空白项为 {@code null}，由调用方按 MEDIUM 兜底）
     */
    public List<ValidationReport> validatePerSource(List<SourceQuerySql> sqls, CrossSourceJoinPlan plan,
            String threadId) {
        List<ValidationReport> reports = new ArrayList<>();
        if (sqls == null) {
            return reports;
        }
        for (SourceQuerySql sql : sqls) {
            if (sql == null || sql.getSqlText() == null || sql.getSqlText().isBlank()) {
                reports.add(null);
                continue;
            }
            int sourceIndex = sql.getSourceIndex() == null ? 0 : sql.getSourceIndex();
            SqlPlanView view = planViewAdapter == null ? null : planViewAdapter.from(plan, sourceIndex);
            int rowLimit = sql.getRowLimit() == null ? defaultRowLimit() : sql.getRowLimit();
            reports.add(guardAdapter.validate(sql.getDsId(), sql.getSqlText(), view, rowLimit, threadId));
        }
        return reports;
    }

    /** 下推规划（委托 {@link SemiJoinPushdownPlanner}，便于单测） */
    public PushdownPlan planPushdown(List<Object> probeKeys, String targetKeyColumn) {
        return pushdownPlanner.plan(probeKeys, targetKeyColumn, properties);
    }

    /**
     * 执行 + 内存 join + 结果合并（api-contract §4.7）。
     *
     * @param plan           join 计划（null / 不可执行 ⇒ 直接失败，不执行任何 SQL）
     * @param sqls           逐源 SQL
     * @param threadId       会话线程 ID
     * @param overallRisk    整体风险等级（诊断/日志用，不改变逐片判级）
     * @param reviewDecision 审核决策（APPROVED 表示已人工放行）
     * @return 执行编排结果（{@code merged} 失败时为 {@code null}）；**不抛异常**
     */
    public CrossSourceExecutionResult executeAndJoin(CrossSourceJoinPlan plan, List<SourceQuerySql> sqls,
            String threadId, String overallRisk, String reviewDecision) {
        List<SourceQuerySql> ordered = orderSources(plan, sqls);
        if (plan == null || !plan.isExecutable() || ordered.size() < 2) {
            log.warn("RCAgent 跨源执行：计划或逐源 SQL 缺失，按失败处理");
            return failure(ordered, 1, CrossSourceConstants.EXEC_FAILED, CrossSourceConstants.FAIL_PLAN_MISSING);
        }
        SourceQuerySql probeSql = ordered.get(0);
        SourceQuerySql targetSql = ordered.get(1);
        if (!guardAdapter.available() || !guardAdapter.executionAvailable()) {
            log.warn("RCAgent 跨源执行：执行/校验服务未注入，按失败处理（fail-closed）");
            return failure(ordered, probeSql.getSourceIndex(), CrossSourceConstants.EXEC_FAILED,
                    CrossSourceConstants.FAIL_SERVICE_MISSING);
        }
        try {
            return doExecute(plan, probeSql, targetSql, threadId, overallRisk, reviewDecision);
        } catch (Exception e) {
            // 「不抛异常」契约：任何执行/校验异常均转为脱敏 FAILED 结果（含「源 N」序号）
            log.warn("RCAgent 跨源执行异常（降级为失败结果）: error={}",
                    CrossSourceConstants.truncate(String.valueOf(e.getMessage()), ERROR_MESSAGE_MAX_LEN));
            return failure(ordered, probeSql.getSourceIndex(), CrossSourceConstants.EXEC_FAILED,
                    CrossSourceConstants.FAIL_PREFIX + CrossSourceConstants.SOURCE_PREFIX
                            + probeSql.getSourceIndex() + " " + CrossSourceConstants.FAIL_EXEC);
        }
    }

    private CrossSourceExecutionResult doExecute(CrossSourceJoinPlan plan, SourceQuerySql probeSql,
            SourceQuerySql targetSql, String threadId, String overallRisk, String reviewDecision) {
        int probeRowLimit = rowLimit(probeSql);
        // ===== 探测侧（PROBE）：先校验再执行（BLOCK 不执行、不继续，整体执行次数 = 0） =====
        SqlPlanView probeView = planViewAdapter == null ? null : planViewAdapter.from(plan, probeSql.getSourceIndex());
        String probeRisk = normalizeRisk(guardAdapter.validate(probeSql.getDsId(), probeSql.getSqlText(),
                probeView, probeRowLimit, threadId));
        if (isBlock(probeRisk)) {
            log.info("RCAgent 跨源执行：探测侧校验 BLOCK，整体失败且两源执行次数=0");
            return failure(List.of(probeSql, targetSql), probeSql.getSourceIndex(),
                    CrossSourceConstants.EXEC_BLOCK_SQL, blockReason(probeSql.getSourceIndex()));
        }
        long probeStart = System.currentTimeMillis();
        GuardedQueryResult probeGuarded = guardAdapter.execute(probeSql.getDsId(), probeSql.getSqlText(),
                probeRowLimit, needsApproval(probeRisk), threadId);
        SourceQueryResult probeResult = toSourceResult(probeSql, probeGuarded, probeRisk,
                System.currentTimeMillis() - probeStart, false, 0, 0);
        if (!probeResult.isSuccess()) {
            log.info("RCAgent 跨源执行：探测侧失败 executionState={}", probeResult.getExecState());
            return failureWith(List.of(probeSql, targetSql), probeResult);
        }
        List<Object> probeKeys = InMemoryHashJoiner.extractKeyValues(probeResult.getDataRows(), plan.getLeftKey());

        // ===== 下推规划（SINGLE_IN / SHARDED_IN / NONE） =====
        PushdownPlan pushdown = planPushdown(probeKeys, plan.getRightKey());

        // ===== 目标侧（TARGET）：逐片「先校验再执行」（任一片 BLOCK → 整体失败且不产出结果） =====
        int targetRowLimit = rowLimit(targetSql);
        SqlPlanView targetView = planViewAdapter == null ? null : planViewAdapter.from(plan, targetSql.getSourceIndex());
        List<List<String>> chunks = pushdown.isPushdownApplied()
                ? pushdown.getChunks() : List.of(List.<String>of());
        List<Map<String, Object>> targetRows = new ArrayList<>();
        Set<String> seenTargetKeys = new LinkedHashSet<>();
        boolean targetTruncated = false;
        long targetCostMs = 0L;
        for (List<String> chunk : chunks) {
            String sql = chunk.isEmpty()
                    ? CrossSourceConstants.stripTrailingSemicolon(targetSql.getSqlText())
                    : injectInCondition(targetSql.getSqlText(), plan.getRightKey(), chunk);
            String shardRisk = normalizeRisk(guardAdapter.validate(targetSql.getDsId(), sql,
                    targetView, targetRowLimit, threadId));
            if (isBlock(shardRisk)) {
                log.info("RCAgent 跨源执行：目标侧分片校验 BLOCK，整体失败且不产出结果");
                return failure(List.of(probeSql, targetSql), targetSql.getSourceIndex(),
                        CrossSourceConstants.EXEC_BLOCK_SQL, blockReason(targetSql.getSourceIndex()));
            }
            long shardStart = System.currentTimeMillis();
            GuardedQueryResult guarded = guardAdapter.execute(targetSql.getDsId(), sql, targetRowLimit,
                    needsApproval(shardRisk), threadId);
            targetCostMs += System.currentTimeMillis() - shardStart;
            QueryResult queryResult = guarded == null ? null : guarded.getResult();
            if (guarded == null || !Boolean.TRUE.equals(guarded.getExecuted()) || queryResult == null) {
                SourceQueryResult failed = toSourceResult(targetSql, guarded, shardRisk, targetCostMs,
                        pushdown.isPushdownApplied(), pushdown.getShardCount(), pushdown.getUnsafeKeyCount());
                return failureWith(List.of(probeSql, targetSql), failed);
            }
            if (Boolean.TRUE.equals(queryResult.getTruncated())) {
                targetTruncated = true;
            }
            for (Map<String, Object> row : queryResult.getRows() == null
                    ? List.<Map<String, Object>>of() : queryResult.getRows()) {
                String token = SemiJoinPushdownPlanner.normalizeKeyToken(
                        keyValue(row, plan.getRightKey()), properties);
                if (token != null && !seenTargetKeys.add(token)) {
                    continue;
                }
                if (targetRows.size() >= targetRowLimit) {
                    targetTruncated = true;
                    break;
                }
                targetRows.add(row);
            }
        }
        SourceQueryResult targetResult = buildTargetResult(targetSql, targetRows, targetTruncated, targetCostMs,
                pushdown);

        // ===== 内存 join + 结果合并 =====
        JoinOutcome joined = joiner.join(probeResult, targetResult, plan, properties);
        CrossSourceQueryResult merged = merger.merge(joined, plan, properties, pushdown.isPushdownApplied());
        log.info("RCAgent 跨源执行完成: probeRowCount={}, targetRowCount={}, mergedRowCount={}, "
                        + "pushdown={}, overallRisk={}, reviewDecision={}",
                probeResult.getRowCount(), targetResult.getRowCount(), merged.getRowCount(),
                pushdown.getPushdownMode(), overallRisk, reviewDecision);
        // 成功路径无失败源（failingSourceIndex = -1）
        return new CrossSourceExecutionResult(merged, List.of(probeResult, targetResult), -1);
    }

    /** 逐源 SQL 排序：探测侧（PROBE / 计划左源）在前，目标侧在后 */
    private List<SourceQuerySql> orderSources(CrossSourceJoinPlan plan, List<SourceQuerySql> sqls) {
        List<SourceQuerySql> ordered = new ArrayList<>();
        if (sqls == null) {
            return ordered;
        }
        List<SourceQuerySql> valid = new ArrayList<>();
        for (SourceQuerySql sql : sqls) {
            if (sql != null && sql.getSqlText() != null && !sql.getSqlText().isBlank()) {
                valid.add(sql);
            }
        }
        Integer probeIndex = plan == null ? null : plan.getLeftSourceIndex();
        SourceQuerySql probe = null;
        SourceQuerySql target = null;
        for (SourceQuerySql sql : valid) {
            if (probe == null && CrossSourceConstants.ROLE_PROBE.equals(sql.getSourceRole())) {
                probe = sql;
            } else if (probe == null && probeIndex != null && probeIndex.equals(sql.getSourceIndex())) {
                probe = sql;
            } else if (target == null) {
                target = sql;
            }
        }
        if (probe == null && !valid.isEmpty()) {
            probe = valid.get(0);
        }
        for (SourceQuerySql sql : valid) {
            if (sql == probe || sql == target) {
                continue;
            }
            if (target == null) {
                target = sql;
            }
        }
        if (probe != null) {
            ordered.add(probe);
        }
        if (target != null) {
            ordered.add(target);
        }
        return ordered;
    }

    /** 探测/目标侧限值（缺省取配置 per-source-row-limit） */
    private int rowLimit(SourceQuerySql sql) {
        if (sql != null && sql.getRowLimit() != null && sql.getRowLimit() > 0) {
            return sql.getRowLimit();
        }
        return defaultRowLimit();
    }

    private int defaultRowLimit() {
        return properties == null ? DEFAULT_ROW_LIMIT : Math.max(1, properties.getPerSourceRowLimit());
    }

    /** 构造目标侧执行摘要（多片去重合并后的结果） */
    private SourceQueryResult buildTargetResult(SourceQuerySql targetSql, List<Map<String, Object>> rows,
            boolean truncated, long costMs, PushdownPlan pushdown) {
        SourceQueryResult result = new SourceQueryResult();
        result.setSourceIndex(targetSql.getSourceIndex());
        result.setDsId(targetSql.getDsId());
        result.setExecState(CrossSourceConstants.EXEC_SUCCESS);
        result.setRowCount(rows.size());
        result.setTruncated(truncated);
        result.setCostMs(costMs);
        result.setColumns(columns(rows));
        result.setPushdownApplied(pushdown.isPushdownApplied());
        result.setPushdownShards(pushdown.isPushdownApplied() ? pushdown.getShardCount() : 0);
        result.setUnsafeKeyCount(pushdown.getUnsafeKeyCount());
        result.setDataRows(rows);
        return result;
    }

    /** 把既有执行结果映射为逐源摘要（列名剥离限定前缀；失败原因脱敏且含「源 N」） */
    private SourceQueryResult toSourceResult(SourceQuerySql sql, GuardedQueryResult guarded, String risk,
            long costMs, boolean pushdownApplied, int shards, int unsafeKeyCount) {
        SourceQueryResult result = new SourceQueryResult();
        result.setSourceIndex(sql.getSourceIndex());
        result.setDsId(sql.getDsId());
        result.setColumns(List.of());
        result.setDataRows(new ArrayList<>());
        result.setPushdownApplied(pushdownApplied);
        result.setPushdownShards(pushdownApplied ? shards : 0);
        result.setUnsafeKeyCount(unsafeKeyCount);
        result.setCostMs(costMs);
        if (guarded == null) {
            result.setExecState(CrossSourceConstants.EXEC_FAILED);
            result.setFailureReason(CrossSourceConstants.FAIL_SERVICE_MISSING);
            return result;
        }
        QueryResult queryResult = guarded.getResult();
        boolean executed = Boolean.TRUE.equals(guarded.getExecuted()) && queryResult != null;
        if (!executed) {
            result.setExecState(resolveFailureState(guarded));
            result.setFailureReason(CrossSourceConstants.truncate(
                    CrossSourceConstants.FAIL_PREFIX + CrossSourceConstants.SOURCE_PREFIX
                            + sql.getSourceIndex() + " " + failureDetail(guarded),
                    CrossSourceConstants.FAILURE_REASON_MAX_LEN));
            return result;
        }
        result.setExecState(CrossSourceConstants.EXEC_SUCCESS);
        result.setRowCount(queryResult.getRowCount() == null ? 0 : queryResult.getRowCount());
        result.setTruncated(Boolean.TRUE.equals(queryResult.getTruncated()));
        if (queryResult.getCostMs() != null && queryResult.getCostMs() > 0) {
            result.setCostMs(queryResult.getCostMs());
        }
        result.setColumns(queryResult.getColumnNames() == null
                ? new ArrayList<>() : stripColumns(queryResult.getColumnNames()));
        result.setDataRows(queryResult.getRows() == null
                ? new ArrayList<>() : new ArrayList<>(queryResult.getRows()));
        return result;
    }

    /** 未执行失败态判定（超时 / 敏感 DENY / 普通失败），仅用于逐源摘要与「源 N」文案 */
    private static String resolveFailureState(GuardedQueryResult guarded) {
        String detail = failureDetail(guarded).toUpperCase(Locale.ROOT);
        if (detail.contains("TIMEOUT") || detail.contains("超时")) {
            return CrossSourceConstants.EXEC_TIMEOUT;
        }
        if (detail.contains("DENY") || detail.contains("REJECT") || detail.contains("敏感")) {
            return CrossSourceConstants.EXEC_REJECTED;
        }
        return CrossSourceConstants.EXEC_FAILED;
    }

    /** 失败明细（取结构化拦截原因/级别原因，截断脱敏；禁止拼接连接信息） */
    private static String failureDetail(GuardedQueryResult guarded) {
        StringBuilder sb = new StringBuilder();
        var risk = guarded.getRisk();
        if (risk != null && risk.getBlockedReason() != null && !risk.getBlockedReason().isBlank()) {
            sb.append(risk.getBlockedReason());
        }
        ValidationReport report = guarded.getReport();
        if (report != null && report.getLevels() != null) {
            for (LevelResult level : report.getLevels()) {
                if (level == null || level.getResult() == null) {
                    continue;
                }
                if (level.getResult().contains("SKIP") || level.getResult().contains("FAIL")) {
                    if (sb.length() > 0) {
                        sb.append(';');
                    }
                    sb.append(level.getResult());
                    if (level.getReason() != null) {
                        sb.append(' ').append(level.getReason());
                    }
                }
            }
        }
        if (sb.length() == 0) {
            sb.append("未实际执行（策略要求审核或服务不可达）");
        }
        return CrossSourceConstants.truncate(sb.toString(), 120);
    }

    /** 列名剥离限定前缀（防库名/表名泄露） */
    private static List<String> stripColumns(List<String> columns) {
        List<String> result = new ArrayList<>();
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                continue;
            }
            String bare = CrossSourceConstants.stripQualifier(column);
            result.add(bare == null || bare.isBlank() ? column.trim() : bare);
        }
        return result;
    }

    /** 从数据行推导列清单（结果集未声明列时使用） */
    private static List<String> columns(List<Map<String, Object>> rows) {
        List<String> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            for (String key : row.keySet()) {
                if (key != null && !key.isBlank() && !result.contains(key)) {
                    result.add(key);
                }
            }
            break;
        }
        return result;
    }

    /** 行内取 join key 值（精确 → 大小写不敏感 → 剥离限定前缀） */
    private static Object keyValue(Map<String, Object> row, String keyName) {
        if (row == null || keyName == null || keyName.isBlank()) {
            return null;
        }
        if (row.containsKey(keyName)) {
            return row.get(keyName);
        }
        String bare = CrossSourceConstants.stripQualifier(keyName);
        if (bare != null && !bare.isBlank() && row.containsKey(bare)) {
            return row.get(bare);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null
                    && (entry.getKey().equalsIgnoreCase(keyName)
                        || (bare != null && entry.getKey().equalsIgnoreCase(bare)))) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 风险归一（与既有 {@code RcaValidateNode.normalizeRisk} 逐字符一致；null/异常源按 MEDIUM） */
    public static String normalizeRisk(ValidationReport report) {
        if (report == null || report.getRisk() == null) {
            return "MEDIUM";
        }
        String level = report.getRisk().getRiskLevel();
        if (level != null && !level.isBlank()) {
            return normalizeRiskText(level);
        }
        if (Boolean.FALSE.equals(report.getPassed())) {
            return "BLOCK";
        }
        return "MEDIUM";
    }

    /** 风险文本归一（BLOCK &gt; HIGH &gt; MEDIUM &gt; LOW，兜底 MEDIUM） */
    private static String normalizeRiskText(String level) {
        String upper = level.toUpperCase(Locale.ROOT);
        if (upper.contains("BLOCK")) {
            return "BLOCK";
        }
        if (upper.contains("HIGH")) {
            return "HIGH";
        }
        if (upper.contains("MEDIUM") || upper.contains("MED")) {
            return "MEDIUM";
        }
        if (upper.contains("LOW")) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private static boolean isBlock(String risk) {
        return "BLOCK".equals(risk);
    }

    private static boolean needsApproval(String risk) {
        return "MEDIUM".equals(risk) || "HIGH".equals(risk);
    }

    private static String blockReason(int sourceIndex) {
        return CrossSourceConstants.FAIL_BLOCKED_PREFIX + sourceIndex + CrossSourceConstants.FAIL_BLOCKED_SUFFIX;
    }

    // ==================== 失败结果构造 ====================

    private CrossSourceExecutionResult failure(List<SourceQuerySql> sqls, int failingIndex, String execState,
            String reason) {
        List<SourceQueryResult> results = new ArrayList<>();
        for (SourceQuerySql sql : sqls) {
            if (sql == null) {
                continue;
            }
            results.add(failureResult(sql, failingIndex, execState, reason));
        }
        if (results.isEmpty()) {
            SourceQueryResult placeholder = new SourceQueryResult();
            placeholder.setSourceIndex(failingIndex);
            placeholder.setExecState(execState);
            placeholder.setFailureReason(CrossSourceConstants.truncate(reason,
                    CrossSourceConstants.FAILURE_REASON_MAX_LEN));
            results.add(placeholder);
        }
        return new CrossSourceExecutionResult(null, results, failingIndex);
    }

    private CrossSourceExecutionResult failureWith(List<SourceQuerySql> sqls, SourceQueryResult failed) {
        int failingIndex = failed.getSourceIndex() == null ? -1 : failed.getSourceIndex();
        List<SourceQueryResult> results = new ArrayList<>();
        for (SourceQuerySql sql : sqls) {
            if (sql == null) {
                continue;
            }
            if (failed.getSourceIndex() != null && failed.getSourceIndex().equals(sql.getSourceIndex())) {
                results.add(failed);
                continue;
            }
            results.add(notExecutedResult(sql, failingIndex, failed.getExecState()));
        }
        return new CrossSourceExecutionResult(null, results, failingIndex);
    }

    private SourceQueryResult failureResult(SourceQuerySql sql, int failingIndex, String execState, String reason) {
        SourceQueryResult result = new SourceQueryResult();
        result.setSourceIndex(sql.getSourceIndex());
        result.setDsId(sql.getDsId());
        result.setColumns(List.of());
        result.setDataRows(new ArrayList<>());
        if (failingIndex == (sql.getSourceIndex() == null ? -1 : sql.getSourceIndex())) {
            result.setExecState(execState);
            result.setFailureReason(CrossSourceConstants.truncate(reason,
                    CrossSourceConstants.FAILURE_REASON_MAX_LEN));
        } else {
            return notExecutedResult(sql, failingIndex, execState);
        }
        return result;
    }

    /** 未执行源摘要（态为 {@link #STATE_NOT_EXECUTED}，**不得**写成 FAILED 以免误报「该源执行失败」） */
    private SourceQueryResult notExecutedResult(SourceQuerySql sql, Integer failingIndex, String failingState) {
        SourceQueryResult result = new SourceQueryResult();
        result.setSourceIndex(sql.getSourceIndex());
        result.setDsId(sql.getDsId());
        result.setExecState(STATE_NOT_EXECUTED);
        result.setFailureReason(REASON_NOT_EXECUTED_PREFIX + failingIndex + notExecutedSuffix(failingState));
        result.setColumns(List.of());
        result.setDataRows(new ArrayList<>());
        return result;
    }

    /** 未执行源说明后缀（失败源被安全体系拦截时保留「已拦截」语义） */
    private static String notExecutedSuffix(String failingState) {
        return CrossSourceConstants.EXEC_BLOCK_SQL.equals(failingState)
                ? REASON_NOT_EXECUTED_BLOCKED_SUFFIX : REASON_NOT_EXECUTED_SUFFIX;
    }

    // ==================== 下推 SQL 构造（仅白名单字面量可拼接） ====================

    /**
     * 把半连接条件注入原只读 SQL：已有 WHERE → 追加 {@code AND <key> IN (...)}；
     * 否则在首个顶层 {@code GROUP BY / ORDER BY / LIMIT} 前插入 {@code WHERE <key> IN (...)}；
     * 无上述子句则追加到末尾。**不改变原 SELECT 列表**（避免 {@code SELECT *} 触发 BLOCK）。
     */
    static String injectInCondition(String originalSql, String keyColumn, List<String> literals) {
        String sql = CrossSourceConstants.stripTrailingSemicolon(originalSql);
        if (sql == null || sql.isBlank() || literals == null || literals.isEmpty()) {
            return sql;
        }
        if (keyColumn == null || keyColumn.isBlank()) {
            return sql;
        }
        String condition = keyColumn.trim() + " IN (" + String.join(", ", literals) + ")";
        int where = topLevelKeyword(sql, "WHERE");
        int groupBy = topLevelKeyword(sql, "GROUP BY");
        int orderBy = topLevelKeyword(sql, "ORDER BY");
        int limit = topLevelKeyword(sql, "LIMIT");
        if (where >= 0) {
            int insertAt = firstPositive(sql.length(), groupBy, orderBy, limit);
            return sql.substring(0, insertAt) + " AND " + condition + " " + sql.substring(insertAt);
        }
        int insertAt = firstPositive(-1, groupBy, orderBy, limit);
        if (insertAt >= 0) {
            return sql.substring(0, insertAt) + " WHERE " + condition + " " + sql.substring(insertAt);
        }
        return sql + " WHERE " + condition;
    }

    /** 取第一个 ≥0 的候选位置（无候选返回 fallback） */
    private static int firstPositive(int fallback, int... positions) {
        int best = -1;
        for (int position : positions) {
            if (position >= 0 && (best < 0 || position < best)) {
                best = position;
            }
        }
        return best >= 0 ? best : fallback;
    }

    /** 查找顶层（括号外、非引号内）关键字位置（大小写不敏感、词边界），未命中返回 -1 */
    static int topLevelKeyword(String sql, String keyword) {
        if (sql == null || keyword == null || keyword.isEmpty()) {
            return -1;
        }
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;
        int length = sql.length();
        for (int i = 0; i < length; i++) {
            char c = sql.charAt(i);
            if (inSingleQuote) {
                if (c == '\'') {
                    inSingleQuote = false;
                }
                continue;
            }
            if (inDoubleQuote) {
                if (c == '"') {
                    inDoubleQuote = false;
                }
                continue;
            }
            if (inBacktick) {
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingleQuote = true;
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                continue;
            }
            if (c == '`') {
                inBacktick = true;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (depth != 0) {
                continue;
            }
            if (regionMatchesKeyword(sql, i, keyword)) {
                return i;
            }
        }
        return -1;
    }

    /** 词边界匹配（前一位必须是标识符外字符，后一位必须非标识符字符） */
    private static boolean regionMatchesKeyword(String sql, int index, String keyword) {
        int end = index + keyword.length();
        if (end > sql.length() || !sql.regionMatches(true, index, keyword, 0, keyword.length())) {
            return false;
        }
        if (index > 0 && isIdentifierChar(sql.charAt(index - 1))) {
            return false;
        }
        return end >= sql.length() || !isIdentifierChar(sql.charAt(end));
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * 跨源执行编排结果。
     *
     * @param merged            合并结果（失败时为 {@code null}，**禁止用部分结果冒充成功**）
     * @param sources           逐源执行摘要（失败诊断与说明用；数据行仅内存）
     * @param failingSourceIndex **真正触发整体失败的源序号**（失败路径显式记录；成功路径为 -1）。
     *                          用于保证诊断中的「源 N」指向被 BLOCK / 失败的真实源，而非未执行源。
     */
    public record CrossSourceExecutionResult(CrossSourceQueryResult merged, List<SourceQueryResult> sources,
            int failingSourceIndex) {

        /** 是否执行成功 */
        public boolean success() {
            return merged != null;
        }

        /**
         * 定位真实失败源（无则返回 {@code null}）。
         *
         * <p>优先返回 {@link #failingSourceIndex} 指向的源（保留其 {@code BLOCK_SQL} / {@code TIMEOUT} /
         * {@code REJECTED} 真实失败态，供节点渲染「已拦截：源 N …」等文案）；未匹配时按失败态优先级
         * （{@code BLOCK_SQL &gt; TIMEOUT &gt; REJECTED &gt; FAILED}）兜底，**跳过「未执行」占位**，
         * 避免把未执行源误报为失败源。</p>
         */
        public SourceQueryResult firstFailure() {
            if (sources == null) {
                return null;
            }
            SourceQueryResult byPriority = null;
            SourceQueryResult firstFailed = null;
            int bestPriority = 0;
            for (SourceQueryResult source : sources) {
                if (source == null || source.isSuccess()) {
                    continue;
                }
                if (firstFailed == null) {
                    firstFailed = source;
                }
                if (source.getSourceIndex() != null && source.getSourceIndex() == failingSourceIndex) {
                    return source;
                }
                int priority = failurePriority(source.getExecState());
                if (priority > bestPriority) {
                    bestPriority = priority;
                    byPriority = source;
                }
            }
            return byPriority == null ? firstFailed : byPriority;
        }

        /** 失败态优先级（{@code NOT_EXECUTED} 占位为 0，仅在无真实失败态时由顺序兜底返回） */
        private static int failurePriority(String execState) {
            if (CrossSourceConstants.EXEC_BLOCK_SQL.equals(execState)) {
                return 4;
            }
            if (CrossSourceConstants.EXEC_TIMEOUT.equals(execState)) {
                return 3;
            }
            if (CrossSourceConstants.EXEC_REJECTED.equals(execState)) {
                return 2;
            }
            if (execState == null || STATE_NOT_EXECUTED.equals(execState)) {
                return 0;
            }
            return 1;
        }
    }
}
