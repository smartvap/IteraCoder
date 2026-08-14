package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.service.GitProjectService;
import com.agenthub.ai.workflow.tool.CodeProjectWriter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 将 generated_code 结构化文本解析为真实项目文件，写出到临时目录。
 * <p>
 * 输入：state.generated_code（包含 // FILE: 标记的完整项目代码）
 * 输出：state.code_project_root（项目临时目录路径）
 * <p>
 * 执行时推送事件到 SSE 通道，使前端能将后续"沙箱验证"步骤立即标记为 active。
 */
@Slf4j
public class CodeProjectWriteNode implements NodeAction {

    public static final String CODE_PROJECT_ROOT = "code_project_root";

    private final WorkflowEventBus eventBus;
    private final GitProjectService gitService;

    public CodeProjectWriteNode(WorkflowEventBus eventBus, GitProjectService gitService) {
        this.eventBus = eventBus;
        this.gitService = gitService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);

        String generatedCode = state.value("generated_code")
                .map(v -> {
                    if (v instanceof org.springframework.ai.chat.messages.AssistantMessage msg) {
                        return msg.getText();
                    }
                    // GraphResponse 包装（asNode 直接输出时）
                    try {
                        java.lang.reflect.Method m = v.getClass().getMethod("resultValue");
                        Object opt = m.invoke(v);
                        if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
                            Object inner = o.get();
                            if (inner instanceof org.springframework.ai.chat.messages.AssistantMessage msg) {
                                return msg.getText();
                            }
                            return inner.toString();
                        }
                    } catch (Exception ignored) {}
                    return v.toString();
                })
                .orElse("");

        if (generatedCode.isBlank()) {
            log.warn("generated_code 为空，跳过项目文件写出");
            pushError(state, "代码生成结果为空，无法写出项目文件");
            return Map.of(CODE_PROJECT_ROOT, "");
        }

        // 注册初始项目目录到 SandboxContext，供修复 Agent 首次创建修复目录时复制基础文件

        CodeProjectWriter.Result result = CodeProjectWriter.write(generatedCode, threadId);
        log.info("项目文件已写出: {} 个文件 → {}", result.writtenFiles().size(), result.projectRoot());
        // 打印文件列表便于调试
        for (String f : result.writtenFiles()) {
            log.info("  {}", f);
        }

        log.info("CodeProjectWriteNode 输出到state: key={}, value={}", CODE_PROJECT_ROOT, result.projectRoot());
        return Map.of(CODE_PROJECT_ROOT, result.projectRoot());
    }

    private void pushEvent(OverAllState state, String key, String content) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId == null) return;
        eventBus.publish(threadId, key, content, "RUNNING");
    }

    private void pushError(OverAllState state, String message) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId == null) return;
        eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, "❌ " + message, "RUNNING");
    }
}
