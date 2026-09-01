package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.tool.SandboxContext;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 修复循环计数节点：每次进入修复分支时递增计数。
 * <p>
 * 如果上一轮修复 Agent 没有写入文件（条件边返回 "retry"），
 * 本节点注入 repair_feedback 到 State，让下一轮修复 Agent 明确知道
 * 上一轮没调 writeCodeFile 工具，必须纠正。
 */
@Slf4j
public class RepairCountIncrementNode implements NodeAction {

    private final WorkflowEventBus eventBus;
    private final int maxRepairIterations;

    public RepairCountIncrementNode(WorkflowEventBus eventBus, int maxRepairIterations) {
        this.eventBus = eventBus;
        this.maxRepairIterations = maxRepairIterations;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        int count = Integer.parseInt(state.value(RdWorkflowKeys.REPAIR_COUNT, 0).toString());
        int next = count + 1;
        log.info("修复循环计数：{} -> {}", count, next);
        Map<String, Object> result = new HashMap<>();
        result.put(RdWorkflowKeys.REPAIR_COUNT, next);
        result.put(RdWorkflowKeys.WORKFLOW_MESSAGE, "第 " + next + " 次代码修复");
        // 将沙箱失败详情写入 State，不受 Agent 报告改写影响
        String err = SandboxContext.getAndClearLastErrorDetail();
        if (err != null && !err.isBlank()) {
            result.put(RdWorkflowKeys.HARNESS_ERROR_DETAIL, err);
        } else {
            // 本轮编译/运行无错误：必须覆盖旧值（置空），
            // 否则前端从 state['harness_error_detail'] 读取到上一轮残留的错误详情，
            // 横幅持续显示已修复的旧错误。
            result.put(RdWorkflowKeys.HARNESS_ERROR_DETAIL, "");
        }

        // 检查上一轮是否写了文件：如果 repairRoot 为空或目录下无文件，
        // 说明上一轮修复 Agent 没调 writeCodeFile，注入强制反馈
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        String repairRoot = SandboxContext.getRepairProjectRootForThread(threadId);

        // 首轮修复时，检测 harness 结果是否存在矛盾：
        // LLM 报告了 COMPILE_SUCCESS: true，但工具返回值中检测到 FAILED/ERROR。
        // 这种矛盾会让修复 Agent 看到全绿报告却不知修什么 → 必须注入警告
        if (count == 0) {
            String harnessResult = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.HARNESS_RESULT, "");
            if (!harnessResult.isBlank()) {
                boolean hasCompileInHarness = harnessResult.matches("(?s).*COMPILE_SUCCESS:\\s*true\\s*$.*");
                boolean hasErrorInHarness = harnessResult.contains("FAILED") || harnessResult.contains("ERROR");
                if (hasCompileInHarness && hasErrorInHarness) {
                    String warning = "⚠️ harness Agent 的输出可能不准确："
                            + "它报告了 COMPILE_SUCCESS: true，但工具返回值中检测到 FAILED/ERROR。"
                            + "请以工具返回值中的实际错误信息为准，忽略报告中的成功标记。"
                            + "你的任务是修复真实编译/测试错误，不是复述 harness 报告。";
                    result.put(RdWorkflowKeys.REPAIR_FEEDBACK, warning);
                    log.warn("首轮修复注入 harness 矛盾警告: hasCompile=true, hasError=true, threadId={}",
                            threadId);
                }
            }
        }

        if (count > 0 && (repairRoot == null || repairRoot.isBlank()
                || !hasSourceFiles(repairRoot))) {
            String feedback = "⚠️ 你在第 " + count + " 轮修复中**未调用 writeCodeFile 或 patchCodeFile 工具** —— "
                    + "没有真正写入任何文件。本轮（第 " + next + " 轮）必须调用 readCodeFile + patchCodeFile（或 writeCodeFile）\n"
                    + "当前是第 " + next + " 轮修复（上限 " + maxRepairIterations + " 轮）";
            result.put(RdWorkflowKeys.REPAIR_FEEDBACK, feedback);
            log.warn("注入修复反馈：第 {} 轮未写文件，第 {} 轮将收到强制提醒", count, next);
            publishWarning(state, "第" + count + "轮修复未写入任何文件，正在重试（第" + next + "轮）");
        } else {
            // 正常/首轮：注入当前轮次信息，Agent 可在修复总结中引用
            result.put(RdWorkflowKeys.REPAIR_FEEDBACK,
                    "当前是第 " + next + " 轮修复（上限 " + maxRepairIterations + " 轮）");
        }

        return result;
    }

    private void publishWarning(OverAllState state, String message) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId != null) {
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, "⚠️ " + message, "RUNNING");
        }
    }

    private static boolean hasSourceFiles(String rootPath) {
        try {
            java.nio.file.Path root = java.nio.file.Path.of(rootPath);
            if (!java.nio.file.Files.isDirectory(root)) {
                return false;
            }
            final java.util.Set<String> extensions = java.util.Set.of(
                    ".java", ".xml", ".properties", ".yml", ".yaml",
                    ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".h", ".hpp"
            );
            try (var stream = java.nio.file.Files.walk(root)) {
                return stream.anyMatch(p -> java.nio.file.Files.isRegularFile(p)
                        && extensions.stream().anyMatch(ext -> p.toString().endsWith(ext)));
            }
        } catch (Exception e) {
            log.warn("检查修复目录文件失败: root={}, error={}", rootPath, e.getMessage());
            return false;
        }
    }
}
