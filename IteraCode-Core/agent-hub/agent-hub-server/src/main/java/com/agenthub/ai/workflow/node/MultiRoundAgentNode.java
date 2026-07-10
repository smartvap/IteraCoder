package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
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
 */
@Slf4j
public class MultiRoundAgentNode implements NodeAction {

    private final ChatModel chatModel;
    private final String instruction;
    private final int maxRounds;
    private final String outputKey;

    private static final Set<String> KNOWN_PLACEHOLDERS = Set.of(
            "requirement", "decomposition_result", "parallel_reasoning_result",
            "generated_code", "harness_result", "repair_count",
            "review_content", "review_decision",
            "review_feedback", "workflow_message", "workflow_status",
            "current_data", "question_answer_context"
    );

    public MultiRoundAgentNode(ChatModel chatModel, String instruction,
            int maxRounds, String outputKey) {
        this.chatModel = chatModel;
        this.instruction = instruction;
        this.maxRounds = maxRounds;
        this.outputKey = outputKey;
    }

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

        StringBuilder allOutputs = new StringBuilder();

        // Round 1: 完整指令调用
        log.info("MultiRoundAgent [{}] Round 1/{}", outputKey, maxRounds);
        String prevOutput = chatModel.call(new Prompt(new UserMessage(fullInstruction)))
                .getResult().getOutput().getText();
        allOutputs.append(prevOutput);

        // Rounds 2..N: 续推调用，携带实际任务数据 + 角色提醒 + 上一轮输出
        for (int round = 2; round <= maxRounds; round++) {
            log.info("MultiRoundAgent [{}] Round {}/{}", outputKey, round, maxRounds);

            String continuation = buildContinuationPrompt(round, maxRounds,
                    prevOutput, taskPrefix, resolvedInstruction);
            String response = chatModel.call(new Prompt(new UserMessage(continuation)))
                    .getResult().getOutput().getText();

            prevOutput = response;
            allOutputs.append("\n\n").append(response);
        }

        Map<String, Object> result = new HashMap<>();
        result.put(outputKey, allOutputs.toString());
        // 显式透传 requirement，防止 StateGraph 框架在节点间序列化时丢失未显式写回的初始输入
        state.value("requirement").ifPresent(v -> result.put("requirement", v));
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
     * 构造防幻觉任务前置块。小模型容易被复杂指令模板误导而输出示例内容（如"在线商城"、
     * "聊天机器人"等与真实需求无关的编造案例），此处将真实需求以最高优先级前置，
     * 并要求模型先复述需求再分析，从机制上阻断幻觉。
     */
    private String buildTaskPrefix(OverAllState state) {
        String requirement = extractStateText(state, "requirement");
        if (requirement.isBlank()) {
            log.warn("MultiRoundAgent [{}] taskPrefix：requirement 为空", outputKey);
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("============================================================\n");
        sb.append("  [防幻觉验证] 在开始任何分析之前，你必须先完成以下步骤：\n");
        sb.append("------------------------------------------------------------\n");
        sb.append("  步骤1：复述你理解的需求（1-2句话即可）\n");
        sb.append("  步骤2：确认以下[真实需求]就是你要分析的唯一对象\n");
        sb.append("  步骤3：开始执行下方指令模板\n");
        sb.append("------------------------------------------------------------\n");
        sb.append("\n");
        sb.append("  >>> 真实需求（这是你需要分析的唯一任务对象）<<<\n");
        sb.append("\n");
        // 每行前加 > 前缀，将需求文本突出显示
        for (String line : requirement.split("\n")) {
            String display = line.length() > 60 ? line.substring(0, 60) : line;
            sb.append("  > ").append(display).append("\n");
        }
        sb.append("\n");
        sb.append("------------------------------------------------------------\n");
        sb.append("  !!! 严禁事项：\n");
        sb.append("  - 禁止分析\"在线商城\"、\"聊天机器人\"、\"注册登录\"等示例项目\n");
        sb.append("  - 禁止输出\"由于需求不明确，我将以XX为例\"\n");
        sb.append("  - 禁止把指令模板中的 [ ] 括号内容当作填空题\n");
        sb.append("  - 你只能分析上面标注的真实需求\n");
        sb.append("============================================================\n\n");

        // 附加其他相关数据（不重复 requirement）
        int extraData = 0;
        extraData += appendStateValue(sb, state, "decomposition_result", "需求拆解结果");
        extraData += appendStateValue(sb, state, "parallel_reasoning_result", "架构设计结果");
        extraData += appendStateValue(sb, state, "generated_code", "当前代码");
        extraData += appendStateValue(sb, state, "harness_result", "沙箱验证结果");
        extraData += appendStateValue(sb, state, "review_feedback", "审核反馈意见");
        extraData += appendStateValue(sb, state, "repair_count", "修复次数");
        // 用 boolean 记录是否有额外数据，但不用它来返回空
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
     * 构建续推提示词：将实际任务数据前置 + 角色指令提醒 + 上一轮输出。
     * <p>
     * 关键改变：增加原始指令的简短摘要作为角色提醒，防止模型在后续轮次中
     * 遗忘任务目标（如架构设计 Agent 滑向代码生成）。
     */
    private String buildContinuationPrompt(int round, int maxRounds,
            String prevOutput, String taskPrefix, String instruction) {
        // 截取指令前 150 字符作为角色提醒，避免全量模板分散小模型注意力
        String shortRole = instruction.length() > 150
                ? instruction.substring(0, 150).replace("\n", " ") + "..."
                : instruction.replace("\n", " ");
        return String.format("""
                %s
                【你的角色】（每轮都需牢记，勿偏离）：%s

                【执行指令】继续完成你的任务，严格执行当前步骤。
                【重要指令】你必须使用中文回答。禁止输出英文。

                当前是第 %d/%d 轮。

                上一轮输出：
                %s

                请基于上述实际任务数据和上一轮输出，继续推进到下一阶段。
                只输出当前轮次应该产出的内容，不要重复之前轮次已经完成的工作。
                """, taskPrefix, shortRole, round, maxRounds, truncate(prevOutput, 4000));
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "\n... [已截断]";
    }
}
