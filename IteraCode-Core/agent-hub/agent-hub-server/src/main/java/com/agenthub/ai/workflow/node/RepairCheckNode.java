package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.tool.SandboxContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 修复有效性校验节点：检查 codeRepair 是否真的通过 writeCodeFile 写了文件。
 * <p>
 * 没有文件 → 跳到 increment_repair 重试（最多 maxRepairIterations 轮）。
 * 有文件 → 继续走 sandbox_init。
 */
@Slf4j
public class RepairCheckNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String repairRoot = SandboxContext.getRepairProjectRoot();
        if (repairRoot == null || repairRoot.isBlank()) {
            log.warn("repair_check: codeRepair 未写入任何文件，跳回修复循环");
            int count = Integer.parseInt(state.value(RdWorkflowKeys.REPAIR_COUNT, 0).toString());
            int next = count + 1;
            return Map.of(
                    RdWorkflowKeys.REPAIR_COUNT, next,
                    RdWorkflowKeys.WORKFLOW_MESSAGE, "第 " + next + " 次代码修复（上一轮未产出文件，重试）"
            );
        }
        log.info("repair_check: 已产修复文件 → {}", repairRoot);
        return Map.of();
    }
}
