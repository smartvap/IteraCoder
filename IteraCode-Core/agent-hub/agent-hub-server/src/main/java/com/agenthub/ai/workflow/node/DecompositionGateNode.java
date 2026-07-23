package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;

import java.util.Map;

/**
 * 拆解结果门控节点：仅在拆解结果正常（非 NOT_DEV_REQ）时到达。
 * 透传节点，不做状态修改，仅提供条件路由的锚点。
 */
public class DecompositionGateNode implements NodeAction {

    private final WorkflowEventBus eventBus;

    public DecompositionGateNode(WorkflowEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        return Map.of();
    }
}
