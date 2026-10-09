package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.service.RcaWorkflowService;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * RCAgent 结果校验/摘要节点：校验 query_result 是否有返回并生成 result_summary。
 * <p>
 * 校验规则：结果为空/异常 → 生成可读摘要并说明无数据证据；有行数据 → 概述行数/首行关键值，
 * 供 /state、历史会话快速回看。对 CHAT 意图（无 SQL）仅汇总检索上下文。
 * 若 query_result 明确为「执行失败/执行降级」（数据源不可达、服务缺失等），额外回写
 * workflow_status=FAILED + errorStatusDetails，与 RcaExecuteNode 形成双保险，确保终态判定 FAILED
 * 而非 COMPLETED（修复审查问题 2）。</p>
 */
@Slf4j
public class RcaResultCheckNode implements NodeAction {

    private final WorkflowEventBus eventBus;

    public RcaResultCheckNode(WorkflowEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String queryResult = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUERY_RESULT, "");
        String retrievalContext = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RETRIEVAL_CONTEXT, "");
        String attributionResult = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.ATTRIBUTION_RESULT, "");
        String answer = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.ANSWER, "");
        String queryAnswer = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUERY_ANSWER, "");
        String intentType = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.INTENT_TYPE, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在校验结果并生成摘要...", "RUNNING");

        // 摘要层面失败关键词（含「未实际执行」的策略性未执行，仅影响摘要文案）
        boolean summaryFailed = queryResult.contains("执行失败") || queryResult.contains("执行降级")
                || queryResult.contains("未实际执行");
        // 终态层面失败：仅「执行失败/执行降级」等真实失败才回写 FAILED（未实际执行可能为策略语义，不误标）
        boolean terminalFailed = queryResult.contains("执行失败") || queryResult.contains("执行降级");

        String summary;
        if ("CHAT".equals(intentType) || queryResult.isBlank()) {
            if (!answer.isBlank()) {
                // F3：答题节点已产出聚焦回答，优先展示；不再转贴整段检索证据
                summary = "【结论】该问题为普通知识问答（" + intentType + "），未执行 SQL 查询。\n"
                        + "【回答】\n" + RcaNodeUtils.truncate(answer, 4000);
            } else {
                // 答题节点缺失/失败（无 answer 键）：回退既有「回答参考（检索证据）」行为
                summary = "【结论】该问题为普通知识问答（" + intentType + "），未执行 SQL 查询。\n"
                        + "回答参考（检索证据）：\n"
                        + RcaNodeUtils.truncate(retrievalContext.isBlank() ? "无检索证据" : retrievalContext, 4000);
            }
        } else if (summaryFailed) {
            summary = "【结论】查询未能取得有效数据：" + RcaNodeUtils.truncate(queryResult, 2000);
        } else {
            // QUERY 成功分支：查数作答节点产出结论时以「结论句 + 明细」呈现；无结论（LLM 失败/空/泄漏）逐字符回退既有文案
            if (!queryAnswer.isBlank()) {
                summary = "【结论】" + RcaNodeUtils.truncate(queryAnswer, 1000) + "\n\n【明细】\n"
                        + RcaNodeUtils.truncate(queryResult, 5000);
            } else {
                summary = "【结论】查询已完成，明细如下：\n" + RcaNodeUtils.truncate(queryResult, 5000);
            }
        }
        if (attributionResult != null && !attributionResult.isBlank()) {
            summary += "\n\n【归因分析】\n" + RcaNodeUtils.truncate(attributionResult, 4000);
        }
        summary = RcaNodeUtils.truncate(summary, 6000);

        log.info("RCAgent 结果校验：summaryLength={}, question='{}'", summary.length(),
                RcaNodeUtils.truncate(question, 80));
        publish(threadId, RcaWorkflowKeys.RESULT_SUMMARY, summary, "RUNNING");

        Map<String, Object> result = new HashMap<>();
        result.put(RcaWorkflowKeys.RESULT_SUMMARY, summary);
        if (terminalFailed) {
            // 执行节点可能已写 FAILED；此处双保险兜底（如历史 checkpoint 恢复等路径漏标）
            if (threadId != null) {
                RcaWorkflowService.errorStatusDetails.put(threadId,
                        "FAILED — " + RcaNodeUtils.truncate(queryResult, 300));
            }
            result.put(RcaWorkflowKeys.WORKFLOW_STATUS, "FAILED");
        }
        return result;
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
