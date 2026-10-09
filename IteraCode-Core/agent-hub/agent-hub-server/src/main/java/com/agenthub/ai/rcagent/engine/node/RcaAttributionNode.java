package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * RCAgent 归因衔接节点（module-007 rca-attribution-workflow 占位契约）。
 * <p>
 * 当用户意图为 ATTRIBUTION 或 query_result 显示异常时，在此衔接归因子链路。
 * P0 阶段：归因分析由 module-007 独立 HTTP 服务承担，本节点做编排衔接与结论占位提示；
 * 依赖服务接口契约确定后在节点内对齐调用（不臆造其内部 API）。</p>
 */
@Slf4j
public class RcaAttributionNode implements NodeAction {

    private final WorkflowEventBus eventBus;

    public RcaAttributionNode(WorkflowEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String queryResult = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUERY_RESULT, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);

        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在归因衔接（依赖归因子链路）...", "RUNNING");

        String attributionResult = """
                【归因衔接说明】
                该问题被识别为归因追问（ATTRIBUTION）或查询结果存在异常，已进入归因分析衔接。
                P0 阶段归因算法/下钻查询由 module-007 rca-attribution-workflow 独立服务承担，
                本节点不臆造其内部 API；待接口契约确定后在此对接：
                - 输入：查询结果（query_result）与用户追问
                - 输出：归因结论（attribution_result）与证据链

                当前查询结果摘要：
                %s
                """.formatted(RcaNodeUtils.truncate(queryResult.isBlank() ? "（无查询结果）" : queryResult, 3000));

        attributionResult = RcaNodeUtils.truncate(attributionResult, 5000);
        log.info("RCAgent 归因衔接完成, threadId={}, question='{}'", threadId,
                RcaNodeUtils.truncate(question, 80));
        publish(threadId, RcaWorkflowKeys.ATTRIBUTION_RESULT, attributionResult, "RUNNING");
        return Map.of(RcaWorkflowKeys.ATTRIBUTION_RESULT, attributionResult);
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
