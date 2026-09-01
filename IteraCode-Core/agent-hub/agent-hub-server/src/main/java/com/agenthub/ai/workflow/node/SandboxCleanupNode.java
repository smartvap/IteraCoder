package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.service.DockerSandboxService;
import com.agenthub.ai.workflow.tool.SandboxContext;

import java.util.Map;

/**
 * 沙箱清理节点：在 harnessAgent 执行后销毁沙箱容器并清除上下文。
 */
public class SandboxCleanupNode implements NodeAction {

    private final DockerSandboxService sandboxService;

    public SandboxCleanupNode(DockerSandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY)
                .map(Object::toString).orElse("");
        // 只清理沙箱容器上下文，保留容器本身（修复循环多轮间复用容器，利用 Maven 缓存）。
        // 容器在工作流结束时由 RdWorkflowService.pushFinalState 统一销毁。
        SandboxContext.clearContainerIdForThread(threadId);
        return Map.of();
    }
}
