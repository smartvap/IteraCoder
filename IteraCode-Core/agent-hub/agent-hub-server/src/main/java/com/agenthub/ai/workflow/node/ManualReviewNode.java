package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.Map;

/**
 * 推理结果人工审核节点：智能体 生成执行摘要 + 原始推理内容，供人工审核决策。
 * <p>
 * 摘要优先流式输出，失败回退同步调用；摘要生成失败不影响审核流程。
 */
@Slf4j
public class ManualReviewNode implements NodeAction {

    private final WorkflowEventBus eventBus;
    private final ChatModel summaryModel;

    public ManualReviewNode(WorkflowEventBus eventBus, ChatModel summaryModel) {
        this.eventBus = eventBus;
        this.summaryModel = summaryModel;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        Object parallelResultObj = state.value(RdWorkflowKeys.PARALLEL_REASONING_RESULT, "");
        Object decompositionObj = state.value(RdWorkflowKeys.DECOMPOSITION_RESULT, "");

        String parallelResult = extractMessageContent(parallelResultObj);
        String decomposition = extractMessageContent(decompositionObj);

        // 1. 智能体 执行摘要（流式优先）
        StringBuilder summaryBuilder = new StringBuilder();
        generateSummary(decomposition, parallelResult, state, summaryBuilder);
        String summary = summaryBuilder.toString().strip();

        // 2. 拼接审核内容
        String reviewContent;
        if (!summary.isBlank()) {
            reviewContent = """
                    ===== 智能体 执行摘要 =====
                    %s

                    ===== 需求拆解结果 =====
                    %s

                    ===== 多模型并行推理结果 =====
                    %s

                    请人工审核以上推理/设计结果，决策：APPROVED(通过) / SENT_BACK(驳回) / TERMINATED(拒绝)
                    """.formatted(summary, decomposition, parallelResult);
        } else {
            reviewContent = """
                    ===== 需求拆解结果 =====
                    %s

                    ===== 多模型并行推理结果 =====
                    %s

                    请人工审核以上推理/设计结果，决策：APPROVED(通过) / SENT_BACK(驳回) / TERMINATED(拒绝)
                    """.formatted(decomposition, parallelResult);
        }

        log.info("人工审核节点：已准备待审核内容（摘要 {} 字符），等待 StateGraph 暂停", summary.length());

        // 推送完整内容（流式摘要已通过 publishDelta 推送，此处推送最终全量保证一致性）
        publish(state, RdWorkflowKeys.REVIEW_CONTENT, reviewContent, "RUNNING");

        Map<String, Object> result = new HashMap<>();
        result.put(RdWorkflowKeys.REVIEW_CONTENT, reviewContent);
        result.put(RdWorkflowKeys.WORKFLOW_STATUS, "WAITING_REVIEW");
        result.put(RdWorkflowKeys.WORKFLOW_MESSAGE, "流程已暂停，等待人工审核推理结果");

        publish(state, RdWorkflowKeys.WORKFLOW_STATUS, "WAITING_REVIEW", "WAITING_REVIEW");
        return result;
    }

    private void generateSummary(String decomp, String parallel, OverAllState state, StringBuilder out) {
        if (summaryModel == null) {
            return;
        }
        String prompt = """
                你是项目审核助理。请为人工审核者提供执行摘要（300 字以内）：

                - **方案概要**：1-2 句话总结
                - **关键决策点**：3-5 条 bullet，标注重要程度
                - **风险提示**：🔴高风险 / 🟡中风险 标注

                不重复原文，只提炼关键信息。

                需求拆解结果：
                %s

                架构方案：
                %s
                """.formatted(decomp, parallel);

        try {
            if (summaryModel instanceof StreamingChatModel) {
                StreamingChatModel streaming = (StreamingChatModel) summaryModel;
                streaming.stream(new Prompt(new UserMessage(prompt)))
                        .doOnNext(chunk -> {
                            String text = chunk.getResult() != null && chunk.getResult().getOutput() != null
                                    ? chunk.getResult().getOutput().getText() : "";
                            if (!text.isEmpty()) {
                                out.append(text);
                                publishDelta(state, text);
                            }
                        })
                        .blockLast();
            } else {
                String result = summaryModel.call(new Prompt(new UserMessage(prompt)))
                        .getResult().getOutput().getText();
                if (result != null) {
                    out.append(result);
                }
            }
        } catch (Exception e) {
            log.warn("智能体 摘要生成失败，跳过摘要", e);
            publishWarning(state, "AI 摘要生成失败，将直接展示原始推理结果: " + e.getMessage());
        }
    }

    private void publishWarning(OverAllState state, String message) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId != null) {
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, "⚠️ " + message, "RUNNING");
        }
    }

    private void publish(OverAllState state, String key, String value, String status) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId != null) {
            eventBus.publish(threadId, key, value, status);
        }
    }

    private void publishDelta(OverAllState state, String delta) {
        if (eventBus == null || delta == null || delta.isEmpty()) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId != null) {
            eventBus.publishDelta(threadId, RdWorkflowKeys.REVIEW_CONTENT, delta, "RUNNING");
        }
    }

    private String extractMessageContent(Object obj) {
        if (obj == null) {
            return "";
        }
        if (obj instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        return obj.toString();
    }
}
