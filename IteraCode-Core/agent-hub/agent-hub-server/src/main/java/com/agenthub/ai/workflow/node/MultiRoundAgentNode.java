package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 多轮循环 Agent 节点：将 skill 的 maxRounds 次 LLM 调用包装为一个 StateGraph 节点。
 * <p>
 * Round 1 使用完整 skill 指令（含已解析的 {placeholder} 变量），
 * Rounds 2..N 以前一轮输出作为上下文继续推进，
 * 实现「聚焦式逐轮推进」的循环工程模式。
 * <p>
 * 每轮结束后通过 WorkflowEventBus 推送事件到 SSE 通道，前端可实时看到进度。
 */
@Slf4j
public class MultiRoundAgentNode implements NodeAction {

    public static final String THREAD_ID_KEY = "_thread_id_";

    private final ChatModel chatModel;
    private final String instruction;
    private final int maxRounds;
    private final String outputKey;
    private final WorkflowEventBus eventBus;

    public MultiRoundAgentNode(ChatModel chatModel, String instruction,
            int maxRounds, String outputKey, WorkflowEventBus eventBus) {
        this.chatModel = chatModel;
        this.instruction = instruction;
        this.maxRounds = maxRounds;
        this.outputKey = outputKey;
        this.eventBus = eventBus;
    }

    private static final Set<String> KNOWN_PLACEHOLDERS = Set.of(
            "requirement", "decomposition_result", "parallel_reasoning_result",
            "generated_code", "harness_result", "repair_count",
            "review_content", "review_decision",
            "review_feedback", "workflow_message", "workflow_status",
            "current_data", "question_answer_context"
    );

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        // 将 skill 指令中的 {placeholder} 替换为 State 中的实际值，并还原 ST4 转义
        String resolvedInstruction = resolveInstruction(instruction, state);
        // 将实际任务数据前置到 prompt 最顶部，防止小模型读完模板后遗忘实际输入
        String taskPrefix = buildTaskPrefix(state);
        // 仅当有实际数据时才添加任务前缀块；空块会让模型误认为"无输入数据"而自行编造
        String fullInstruction = taskPrefix.isBlank()
                ? resolvedInstruction
                : taskPrefix + resolvedInstruction;

        log.info("MultiRoundAgent [{}] state keys: requirement='{}', decomposition='{}'",
                outputKey,
                state.value("requirement").map(Object::toString).orElse("<MISSING>"),
                state.value("decomposition_result").map(v -> {
                    String s = v.toString();
                    return s.length() > 80 ? s.substring(0, 80) + "..." : s;
                }).orElse("<MISSING>"));

        // 并行推理节点启动时立即通知前端切换步骤
        if (outputKey.startsWith("reasoning_")) {
            pushEvent(state, "");
        }

        StringBuilder allOutputs = new StringBuilder();

        // Round 1: 流式调用
        log.info("MultiRoundAgent [{}] Round 1/{}", outputKey, maxRounds);
        String prevOutput = streamCall(new Prompt(new UserMessage(fullInstruction)), state, allOutputs);

        // [NOT_DEV_REQ] 标记：非研发需求，立即终止不走后续轮次
        if (RdWorkflowKeys.isNotDevReq(prevOutput)) {
            log.info("MultiRoundAgent [{}] 检测到 [NOT_DEV_REQ]，跳过后续轮次", outputKey);
        } else {
            // Rounds 2..N: 续推流式调用
            for (int round = 2; round <= maxRounds; round++) {
            log.info("MultiRoundAgent [{}] Round {}/{}", outputKey, round, maxRounds);

            String continuation = buildContinuationPrompt(round, maxRounds,
                    prevOutput, taskPrefix, resolvedInstruction);
            allOutputs.append("\n\n");
            pushEventDelta(state, "\n\n");
            String response = streamCall(new Prompt(new UserMessage(continuation)), state, allOutputs);

            prevOutput = response;
            }
        }

        pushEvent(state, allOutputs.toString());

        Map<String, Object> result = new HashMap<>();
        result.put(outputKey, allOutputs.toString());
        // 显式透传 requirement，防止 StateGraph 框架在节点间序列化时丢失未显式写回的初始输入
        state.value("requirement").ifPresent(v ->         result.put("requirement", v));
        return result;
    }

    /**
     * 流式调用 ChatModel，逐 token 追加到 buffer 并实时推送增量事件。
     * 推送增量而非全量，避免代码生成后期每个事件携带 20K+ 字符导致浏览器渲染卡顿。
     */
    private String streamCall(Prompt prompt, OverAllState state, StringBuilder buffer) {
        if (chatModel instanceof StreamingChatModel) {
            StreamingChatModel streaming = (StreamingChatModel) chatModel;
            streaming.stream(prompt)
                    .doOnNext(chunk -> {
                        String text = chunk.getResult() != null && chunk.getResult().getOutput() != null
                                ? chunk.getResult().getOutput().getText() : "";
                        if (!text.isEmpty()) {
                            buffer.append(text);
                            pushEventDelta(state, text);
                            if (buffer.length() % 500 == 0 || buffer.length() < 20) {
                                log.info("MultiRoundAgent [{}] 流式进度: {} 字符", outputKey, buffer.length());
                            }
                        }
                    })
                    .doOnComplete(() -> log.info("MultiRoundAgent [{}] 流式完成，总字符: {}", outputKey, buffer.length()))
                    .blockLast();
            // 清空增量节流缓存，推送本轮完整内容保证前端最终一致性
            flushDelta(state);
            return buffer.toString();
        }
        // 回退到阻塞调用
        String result = chatModel.call(prompt).getResult().getOutput().getText();
        buffer.append(result);
        return result;
    }

    /**
     * 将 skill 指令中的 {placeholder} 替换为 OverAllState 中的实际文本值，
     * 并将 ST4 转义的 \{ \} 还原为普通 { }
     */
    private String resolveInstruction(String text, OverAllState state) {
        if (text == null) {
            return "";
        }
        String resolved = text.replace("\\{", "{").replace("\\}", "}");
        for (String key : KNOWN_PLACEHOLDERS) {
            String placeholder = "{" + key + "}";
            if (resolved.contains(placeholder)) {
                resolved = resolved.replace(placeholder, extractStateText(state, key));
            }
        }
        return resolved;
    }

    /**
     * 极简任务前置块（Loops > Prompts 思想）。
     * 不写长仪式 prompt，只提供事实数据，让模型靠多轮自查而非前置约束来保证质量。
     */
    private String buildTaskPrefix(OverAllState state) {
        String requirement = extractStateText(state, "requirement");
        if (requirement.isBlank()) {
            log.warn("MultiRoundAgent [{}] taskPrefix：requirement 为空", outputKey);
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【任务】\n");
        sb.append(requirement).append("\n\n");

        // 附加引用数据
        appendStateValue(sb, state, "decomposition_result", "需求拆解结果");
        appendStateValue(sb, state, "parallel_reasoning_result", "架构设计结果");
        appendStateValue(sb, state, "generated_code", "当前代码");
        appendStateValue(sb, state, "harness_result", "沙箱验证结果");
        appendStateValue(sb, state, "review_feedback", "审核反馈意见");
        appendStateValue(sb, state, "repair_count", "修复次数");
        return sb.toString();
    }

    private int appendStateValue(StringBuilder sb, OverAllState state, String key, String label) {
        String value = extractStateText(state, key);
        if (!value.isBlank()) {
            String display = value.length() > 1500 ? value.substring(0, 1500) + "\n... [已截断]" : value;
            sb.append("--- ").append(label).append(" ---\n").append(display).append("\n\n");
            return 1;
        }
        return 0;
    }

    /**
     * 从 OverAllState 中提取纯文本值，正确处理 AssistantMessage 等包装类型。
     * State 中的 Agent 输出通常存储为 AssistantMessage，直接 toString() 会得到
     * "AssistantMessage{...}" 导致模型无法理解。
     */
    private String extractStateText(OverAllState state, String key) {
        return state.value(key)
                .map(v -> {
                    if (v instanceof AssistantMessage msg) {
                        return msg.getText();
                    }
                    if (v instanceof Message msg) {
                        return msg.getText();
                    }
                    return v.toString();
                })
                .orElse("");
    }

    /**
     * 构建续推提示词：只提供任务数据、角色摘要和上一轮输出，不做过度约束。
     * 后续轮次的质量交给 Loop 自查，而非前置 prompt 仪式。
     */
    private String buildContinuationPrompt(int round, int maxRounds,
            String prevOutput, String taskPrefix, String instruction) {
        String shortRole = instruction.length() > 150
                ? instruction.substring(0, 150).replace("\n", " ") + "..."
                : instruction.replace("\n", " ");
        return String.format("""
                %s
                你的角色：%s

                第 %d/%d 轮。上一轮输出：
                %s

                继续推进到下一阶段，不要重复已完成的步骤。
                """, taskPrefix, shortRole, round, maxRounds, truncate(prevOutput, 4000));
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "\n... [已截断]";
    }

    private void pushEvent(OverAllState state, String content) {
        if (eventBus == null) return;
        String threadId = state.value(THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId == null) return;
        eventBus.publish(threadId, outputKey, content, "RUNNING");
    }

    /**
     * 推送增量事件：与 pushEvent 使用相同的 outputKey，
     * 前端通过累积拼接实现流式渲染。
     * 每 100ms 或累积满 300 字符推送一次，节流期间跳过的 delta 会被累积后一起推送。
     */
    private static class ThrottleState {
        long lastPushTime;
        final StringBuilder pending = new StringBuilder();
    }
    private final java.util.Map<String, ThrottleState> throttleMap = new java.util.concurrent.ConcurrentHashMap<>();

    private void pushEventDelta(OverAllState state, String delta) {
        if (eventBus == null || delta == null || delta.isEmpty()) return;
        String threadId = state.value(THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId == null) return;

        String throttleKey = threadId + "_" + outputKey;
        ThrottleState ts = throttleMap.computeIfAbsent(throttleKey, k -> new ThrottleState());
        ts.pending.append(delta);

        long now = System.currentTimeMillis();
        if (now - ts.lastPushTime > 50 || ts.pending.length() > 80) {
            eventBus.publishDelta(threadId, outputKey, ts.pending.toString(), "RUNNING");
            log.debug("MultiRoundAgent [{}] 推送增量: {} 字符, 间隔 {} ms",
                    outputKey, ts.pending.length(), now - ts.lastPushTime);
            ts.pending.setLength(0);
            ts.lastPushTime = now;
        }
    }

    private void flushDelta(OverAllState state) {
        if (eventBus == null) return;
        String threadId = state.value(THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId == null) return;
        String throttleKey = threadId + "_" + outputKey;
        ThrottleState ts = throttleMap.remove(throttleKey);
        // 推送最后一批残留的 pending delta
        if (ts != null && ts.pending.length() > 0) {
            eventBus.publishDelta(threadId, outputKey, ts.pending.toString(), "RUNNING");
        }
    }
}
