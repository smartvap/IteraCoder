package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.crosssource.CrossSourceConstants;
import com.agenthub.ai.rcagent.crosssource.CrossSourceExecutionService;
import com.agenthub.ai.rcagent.crosssource.CrossSourceExecutionService.CrossSourceExecutionResult;
import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceQueryResult;
import com.agenthub.ai.rcagent.crosssource.model.SourceQueryResult;
import com.agenthub.ai.rcagent.crosssource.model.SourceQuerySql;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.service.RcaWorkflowService;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RCAgent 跨源执行节点（module-003，新增）：逐源执行 + 半连接下推 + 内存 join + 结果合并。
 *
 * <p>分支契约（api-contract §4.5.1 E0~E9）：计划 / 逐源 SQL 缺失或解析失败 → FAILED；
 * 执行服务未注入 → FAILED（**不得**用部分结果冒充成功）；成功 → 写
 * {@code query_result}（{@link CrossSourceQueryResult#toDisplayText()} 逐字符版式）+
 * {@code source_query_results}（逐源摘要，**不含数据行**）+ {@code cross_source_note}；
 * 失败 → {@code query_result} 失败文案 + {@code workflow_status=FAILED} +
 * {@code errorStatusDetails}（≤300，脱敏，含「源 N」）+ {@code cross_source_note}。</p>
 *
 * <p><b>异常契约</b>：执行服务异常**不抛出**（转为 FAILED 结果，保证 pushFinalState 判定 FAILED）；
 * publish 纪律：仅 publish {@code cross_source_note} / {@code workflow_message} / {@code workflow_status}
 * 与失败文案，**禁止** publish 任何含 SQL / dsId 的键。</p>
 */
@Slf4j
public class RcaCrossSourceExecuteNode implements NodeAction {

    private final WorkflowEventBus eventBus;
    private final CrossSourceExecutionService executionService;
    private final CrossSourceProperties properties;

    public RcaCrossSourceExecuteNode(WorkflowEventBus eventBus, CrossSourceExecutionService executionService,
            CrossSourceProperties properties) {
        this.eventBus = eventBus;
        this.executionService = executionService;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String threadId = threadId(state);
        String joinPlanText = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.JOIN_PLAN, "");
        String plansJson = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SOURCE_QUERY_PLANS, "");
        String overallRisk = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RISK_LEVEL, "");
        String reviewDecision = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.REVIEW_DECISION, "");

        // E1：计划或逐源 SQL 缺失 / JSON 解析失败 → FAILED
        CrossSourceJoinPlan plan = parsePlan(joinPlanText);
        List<SourceQuerySql> sqls = parseSqls(plansJson);
        if (plan == null || !plan.isExecutable() || sqls.size() < 2) {
            log.warn("RCAgent 跨源执行：计划或逐源 SQL 缺失，按失败处理");
            return failure(threadId, 1, CrossSourceConstants.FAIL_PLAN_MISSING);
        }
        // E2：执行服务未注入 → FAILED（不得用部分结果冒充成功）
        if (executionService == null) {
            log.warn("RCAgent 跨源执行：执行服务未注入，按失败处理");
            return failure(threadId, plan.getLeftSourceIndex() == null ? 1 : plan.getLeftSourceIndex(),
                    CrossSourceConstants.FAIL_SERVICE_MISSING);
        }
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                "正在跨源执行只读查询（风险等级 " + overallRisk + "）...", "RUNNING");

        CrossSourceExecutionResult result;
        try {
            result = executionService.executeAndJoin(plan, sqls, threadId, overallRisk, reviewDecision);
        } catch (Exception e) {
            // 「不抛异常」契约：执行服务异常转为 FAILED 结果
            log.warn("RCAgent 跨源执行异常: {}",
                    CrossSourceConstants.truncate(String.valueOf(e.getMessage()), 200));
            return failure(threadId, plan.getLeftSourceIndex() == null ? 1 : plan.getLeftSourceIndex(),
                    CrossSourceConstants.FAIL_PREFIX + CrossSourceConstants.SOURCE_PREFIX
                            + (plan.getLeftSourceIndex() == null ? 1 : plan.getLeftSourceIndex())
                            + CrossSourceConstants.FAIL_EXEC);
        }
        if (result == null || !result.success()) {
            // E9：任一源失败/超时/分片校验失败 → FAILED（不写合并结果）
            SourceQueryResult failed = result == null ? null : result.firstFailure();
            int sourceIndex = failed == null || failed.getSourceIndex() == null
                    ? (plan.getLeftSourceIndex() == null ? 1 : plan.getLeftSourceIndex())
                    : failed.getSourceIndex();
            String note = resolveFailureNote(failed, sourceIndex);
            log.info("RCAgent 跨源执行失败: sourceIndex={}, execState={}",
                    sourceIndex, failed == null ? null : failed.getExecState());
            return failure(threadId, sourceIndex, note);
        }

        // E8：成功 → 合并结果文本 + 逐源摘要（不含数据行）+ 脱敏跨源说明
        CrossSourceQueryResult merged = result.merged();
        String queryResult = merged.toDisplayText();
        String summaries = JSON.toJSONString(result.sources() == null
                ? new ArrayList<SourceQueryResult>() : result.sources());
        String note = CrossSourceConstants.truncate(merged.getNote(),
                properties == null ? 500 : properties.getNoteMaxLen());
        log.info("RCAgent 跨源执行完成: mergedRowCount={}, truncated={}, noteLength={}",
                merged.getRowCount(), merged.isTruncated(), note == null ? 0 : note.length());
        publish(threadId, RcaWorkflowKeys.QUERY_RESULT, queryResult, "RUNNING");
        publish(threadId, RcaWorkflowKeys.CROSS_SOURCE_NOTE, note, "RUNNING");
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                "跨源查询完成，共合并 " + merged.getRowCount() + " 行", "RUNNING");
        Map<String, Object> output = new HashMap<>();
        output.put(RcaWorkflowKeys.QUERY_RESULT, queryResult);
        output.put(RcaWorkflowKeys.SOURCE_QUERY_RESULTS, summaries);
        output.put(RcaWorkflowKeys.CROSS_SOURCE_NOTE, note);
        return output;
    }

    /** 失败说明（data-model §5.9 版式，仅含「源 N」序号） */
    private static String resolveFailureNote(SourceQueryResult failed, int sourceIndex) {
        String state = failed == null ? null : failed.getExecState();
        if (CrossSourceConstants.EXEC_BLOCK_SQL.equals(state)) {
            return CrossSourceConstants.FAIL_BLOCKED_PREFIX + sourceIndex + CrossSourceConstants.FAIL_BLOCKED_SUFFIX;
        }
        if (CrossSourceConstants.EXEC_TIMEOUT.equals(state)) {
            return CrossSourceConstants.FAIL_PREFIX + CrossSourceConstants.SOURCE_PREFIX + sourceIndex
                    + " " + CrossSourceConstants.FAIL_TIMEOUT;
        }
        return CrossSourceConstants.FAIL_PREFIX + CrossSourceConstants.SOURCE_PREFIX + sourceIndex
                + " " + CrossSourceConstants.FAIL_EXEC;
    }

    /** 失败收尾（query_result 失败文案 + workflow_status=FAILED + errorStatusDetails + cross_source_note） */
    private Map<String, Object> failure(String threadId, int sourceIndex, String note) {
        String message = note;
        if (threadId != null) {
            RcaWorkflowService.errorStatusDetails.put(threadId,
                    "FAILED — " + CrossSourceConstants.truncate(note, CrossSourceConstants.ERROR_DETAIL_MAX_LEN));
        }
        publish(threadId, RcaWorkflowKeys.WORKFLOW_STATUS, "FAILED", "FAILED");
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, message, "FAILED");
        publish(threadId, RcaWorkflowKeys.CROSS_SOURCE_NOTE, note, "FAILED");
        Map<String, Object> output = new HashMap<>();
        output.put(RcaWorkflowKeys.QUERY_RESULT, message);
        output.put(RcaWorkflowKeys.WORKFLOW_STATUS, "FAILED");
        output.put(RcaWorkflowKeys.CROSS_SOURCE_NOTE, note);
        return output;
    }

    private CrossSourceJoinPlan parsePlan(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json, CrossSourceJoinPlan.class);
        } catch (Exception e) {
            log.warn("RCAgent 跨源执行：join_plan 解析失败");
            return null;
        }
    }

    private List<SourceQuerySql> parseSqls(String json) {
        List<SourceQuerySql> sqls = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return sqls;
        }
        try {
            List<SourceQuerySql> parsed = JSON.parseArray(json, SourceQuerySql.class);
            if (parsed != null) {
                for (SourceQuerySql sql : parsed) {
                    if (sql != null && sql.getSqlText() != null && !sql.getSqlText().isBlank()) {
                        sqls.add(sql);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("RCAgent 跨源执行：逐源 SQL JSON 解析失败");
        }
        return sqls;
    }

    private static String threadId(OverAllState state) {
        if (state == null) {
            return null;
        }
        return state.value(RcaWorkflowKeys.THREAD_ID_KEY).map(Object::toString).orElse(null);
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) {
            return;
        }
        eventBus.publish(threadId, key, value, status);
    }
}
