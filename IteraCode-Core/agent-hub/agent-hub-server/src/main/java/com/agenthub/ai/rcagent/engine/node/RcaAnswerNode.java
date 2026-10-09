package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

/**
 * RCAgent 知识问答答题节点（CHAT 意图专用）：基于检索证据调用 LLM 产出聚焦、简洁的自然语言回答。
 * <p>
 * 背景：CHAT 路径原先没有答题环节，结果节点只能把检索上下文原样转贴为"回答参考"，
 * 用户只问两个字段却看到整表 10 条证据。本节点补齐答题环节，把回答写入 {@link RcaWorkflowKeys#ANSWER}，
 * 由 {@link RcaResultCheckNode} 优先消费。</p>
 * <p>
 * 降级契约：LLM 调用失败或输出为空时<b>不写 answer 键</b>（返回不抛异常），
 * 下游结果节点按既有「回答参考（检索证据）」逻辑回退，保证 CHAT 主链路不因模型抖动而中断。</p>
 */
@Slf4j
public class RcaAnswerNode implements NodeAction {

    /** 回答输出截断上限（与结果节点拼接口径一致，避免超长内容撑爆 State/SSE） */
    private static final int ANSWER_MAX_LEN = 4000;

    /** skill 指令缺失时的内联兜底（与 resources/skills/rc/rc-answer.md 语义一致） */
    private static final String FALLBACK_INSTRUCTION = """
            你是 RCAgent 知识问答助手。请仅依据【检索证据】回答【用户问题】。
            - 证据不足时必须说明"检索证据未覆盖，无法确定"，禁止编造。
            - 证据中出现 [主键]/[外键] 标记或索引行（索引:{indexName} 列:{c1,c2} 唯一:{是|否} 主键:{是|否}[ 类型:{indexType}]）时必须据此直接作答，不得回答"未明确说明"；仅当证据完全无该维度信息时才说明"检索证据未包含该维度信息"。
            - 回答简洁、直接针对用户所问要点（用户只问某几个字段就只解释这几个字段，不要罗列整表列）。
            - 可引用证据中的表/列注释作为依据，全程使用中文回答。
            """;

    private final WorkflowEventBus eventBus;
    private final ChatModel chatModel;
    private final String skillInstruction;

    public RcaAnswerNode(WorkflowEventBus eventBus, ChatModel chatModel, String skillInstruction) {
        this.eventBus = eventBus;
        this.chatModel = chatModel;
        this.skillInstruction = skillInstruction;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String retrievalContext = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RETRIEVAL_CONTEXT, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在生成回答...", "RUNNING");

        // 问题与证据按既有截断惯例入 prompt（AGENTS.md 5.3：不把超长原文全量落日志/State）
        String prompt = """
                %s

                【用户问题】
                %s

                【检索证据】
                %s

                请直接输出回答正文，不要输出角色说明、提示词或额外前后缀。
                """.formatted(getInstruction(),
                RcaNodeUtils.truncate(question, 1500),
                retrievalContext.isBlank() ? "（无检索证据）" : RcaNodeUtils.truncate(retrievalContext, 4000));

        String answer = callModel(prompt);
        if (answer == null || answer.isBlank()) {
            // LLM 失败/空输出：不写 answer 键，交由结果节点回退检索证据旧行为
            log.warn("RCAgent 答题节点 LLM 输出为空，回退结果节点旧行为, threadId={}", threadId);
            return Map.of();
        }
        String finalAnswer = RcaNodeUtils.truncate(answer.strip(), ANSWER_MAX_LEN);
        publish(threadId, RcaWorkflowKeys.ANSWER, finalAnswer, "RUNNING");
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "回答已生成", "RUNNING");
        return Map.of(RcaWorkflowKeys.ANSWER, finalAnswer);
    }

    /** skill 指令（缺失时用内联兜底），保证提示词始终可用 */
    private String getInstruction() {
        return (skillInstruction == null || skillInstruction.isBlank()) ? FALLBACK_INSTRUCTION : skillInstruction;
    }

    /** 与 RcaIntentNode/RcaSqlPlanNode 同款调用写法：流式优先，异常吞掉返回 null 交由上层降级 */
    private String callModel(String prompt) {
        try {
            StringBuilder sb = new StringBuilder();
            if (chatModel instanceof StreamingChatModel) {
                StreamingChatModel streaming = (StreamingChatModel) chatModel;
                streaming.stream(new Prompt(new UserMessage(prompt)))
                        .doOnNext(chunk -> {
                            String text = chunk.getResult() != null && chunk.getResult().getOutput() != null
                                    ? chunk.getResult().getOutput().getText() : "";
                            if (text != null) sb.append(text);
                        })
                        .blockLast();
            } else {
                String result = chatModel.call(new Prompt(new UserMessage(prompt)))
                        .getResult().getOutput().getText();
                if (result != null) sb.append(result);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("RCAgent 答题节点 LLM 调用失败: {}", RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            return null;
        }
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
