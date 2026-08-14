package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 工作流初始化节点：重置循环计数与状态，并显式透传 requirement 确保不会丢失。
 * <p>
 * 部分 StateGraph 实现可能不会自动合并初始输入到每个节点的 state 中，
 * 因此此处显式将 requirement 重新写入，确保下游节点一定能读取到。
 */
@Slf4j
public class WorkflowInitNode implements NodeAction {

    private final WorkflowEventBus eventBus;

    public WorkflowInitNode(WorkflowEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        Object requirement = state.value(RdWorkflowKeys.REQUIREMENT).orElse("");
        log.info("工作流初始化：requirement={}", requirement);

        Map<String, Object> result = new HashMap<>();
        // 显式透传 requirement，防止框架在节点间丢失初始输入
        result.put(RdWorkflowKeys.REQUIREMENT, requirement);
        result.put(RdWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
        result.put(RdWorkflowKeys.WORKFLOW_MESSAGE, "工作流已启动");
        // 初始化审核字段为空串，避免 STS 模板引擎严格模式因占位符缺失抛异常。
        // 但如果已有值（如 SENT_BACK 驳回重跑、recover 恢复），则保留不覆盖。
        String existingDecision = String.valueOf(state.value(RdWorkflowKeys.REVIEW_DECISION).orElse(""));
        String existingFeedback = String.valueOf(state.value(RdWorkflowKeys.REVIEW_FEEDBACK).orElse(""));
        if (existingDecision.isEmpty()) {
            result.put(RdWorkflowKeys.REVIEW_DECISION, "");
        }
        if (existingFeedback.isEmpty()) {
            result.put(RdWorkflowKeys.REVIEW_FEEDBACK, "");
        }

        publish(state, RdWorkflowKeys.REQUIREMENT, requirement.toString());
        publish(state, RdWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
        publish(state, RdWorkflowKeys.WORKFLOW_MESSAGE, "工作流已启动");
        return result;
    }

    private void publish(OverAllState state, String key, String value) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId != null) {
            eventBus.publish(threadId, key, value, "RUNNING");
        }
    }
}
