package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * RCAgent 反馈落点节点（module-008 feedback-loop 占位契约）。
 * <p>
 * 工作流正常结束时提示用户可提交反馈；真实反馈由 POST /feedback 接收并记录到 metadata.remark，
 * 待 feedback-loop 接口契约确定后在此对齐转发（不臆造其内部 API）。</p>
 */
@Slf4j
public class RcaFeedbackNode implements NodeAction {

    private final WorkflowEventBus eventBus;

    public RcaFeedbackNode(WorkflowEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        String message = "会话已结束，您可以对结果提交反馈（有用/无用/纠错）。";
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, message, "RUNNING");
        return Map.of();
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
