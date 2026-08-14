package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

/**
 * 并行推理结果智能合并节点：使用 LLM 将多路差异化推理结果融合为一份精炼方案。
 *
 */
@Slf4j
public class ParallelResultMergeNode implements NodeAction {

    private final List<String> reasoningModels;
    private final ChatModel mergeModel;
    private final WorkflowEventBus eventBus;

    public ParallelResultMergeNode(List<String> reasoningModels, ChatModel mergeModel, WorkflowEventBus eventBus) {
        this.reasoningModels = reasoningModels;
        this.mergeModel = mergeModel;
        this.eventBus = eventBus;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        // 1. 收集各路输出
        StringBuilder rawOutputs = new StringBuilder();
        for (int i = 0; i < reasoningModels.size(); i++) {
            String modelName = reasoningModels.get(i).trim();
            String role = (i == 0) ? "架构设计" : "API 契约设计";
            String key = toReasoningKey(modelName) + "_result";
            String result = extractText(state, key);

            rawOutputs.append("## ").append(modelName).append("（").append(role).append("）\n");
            if (result.isBlank()) {
                rawOutputs.append("（该模型未产出结果）\n");
            } else {
                rawOutputs.append(result).append("\n");
            }
            rawOutputs.append("\n");
        }

        // 2. 通知前端开始合并
        publish(state, RdWorkflowKeys.PARALLEL_REASONING_RESULT, "正在智能融合并行推理结果...\n");

        // 微延迟：让 SSE 事件渲染一帧
        Thread.sleep(200);

        // 3. LLM 智能融合（优先流式，回退同步）
        final StringBuilder mergedBuilder = new StringBuilder();
        try {
            String mergePrompt = """
                    你是方案整合专家。请将以下两份互补的技术方案融合为一份完整的最终方案。

                    两份方案的视角不同：
                    - 一份是架构设计（技术栈、模块划分、数据模型）
                    - 一份是 API 契约设计（接口列表、业务流程、跨模块交互）

                    融合要求：
                    1. 去重：相同内容只保留一份，不重复
                    2. 补全：如果某方遗漏了另一方覆盖的内容，补充进去
                    3. 结构清晰：用 ## 标题分层组织
                    4. 不要遗漏任何一方的关键信息

                    原始方案如下：

                    """.formatted() + rawOutputs.toString();

            if (mergeModel instanceof StreamingChatModel) {
                StreamingChatModel streaming = (StreamingChatModel) mergeModel;
                streaming.stream(new Prompt(new UserMessage(mergePrompt)))
                        .doOnNext(chunk -> {
                            String text = chunk.getResult() != null && chunk.getResult().getOutput() != null
                                    ? chunk.getResult().getOutput().getText() : "";
                            if (!text.isEmpty()) {
                                mergedBuilder.append(text);
                                publishDelta(state, text);
                            }
                        })
                        .doOnComplete(() -> log.info("LLM 融合流式完成，总字符: {}", mergedBuilder.length()))
                        .blockLast();
            } else {
                String mergeResult = mergeModel.call(new Prompt(new UserMessage(mergePrompt)))
                        .getResult().getOutput().getText();
                mergedBuilder.append(mergeResult);
            }
        } catch (Exception e) {
            log.warn("LLM 融合失败，回退到简单拼接", e);
            publishWarning(state, "LLM 智能融合失败，已降级为简单拼接: " + e.getMessage());
            mergedBuilder.setLength(0);
            mergedBuilder.append(rawOutputs.toString());
        }

        String merged = mergedBuilder.toString().strip();
        if (merged.isBlank()) {
            merged = rawOutputs.toString();
        }
        log.info("LLM 融合完成，结果长度: {}", merged.length());

        // 4. 推送最终结果
        publish(state, RdWorkflowKeys.PARALLEL_REASONING_RESULT, merged);

        log.info("并行推理结果已智能融合（模型数: {}）", reasoningModels.size());
        return Map.of(RdWorkflowKeys.PARALLEL_REASONING_RESULT, merged);
    }

    private void publish(OverAllState state, String key, String value) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId != null) {
            eventBus.publish(threadId, key, value, "RUNNING");
        }
    }

    private void publishDelta(OverAllState state, String delta) {
        if (eventBus == null || delta == null || delta.isEmpty()) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId != null) {
            eventBus.publishDelta(threadId, RdWorkflowKeys.PARALLEL_REASONING_RESULT, delta, "RUNNING");
        }
    }

    private void publishWarning(OverAllState state, String message) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId != null) {
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, "⚠️ " + message, "RUNNING");
        }
    }

    private static String toReasoningKey(String modelName) {
        return "reasoning_" + modelName.trim().replace("-", "_").replace(".", "_");
    }

    private String extractText(OverAllState state, String key) {
        return state.value(key)
                .map(value -> {
                    if (value instanceof Message message) {
                        return message.getText();
                    }
                    return value.toString();
                })
                .orElse("");
    }
}
