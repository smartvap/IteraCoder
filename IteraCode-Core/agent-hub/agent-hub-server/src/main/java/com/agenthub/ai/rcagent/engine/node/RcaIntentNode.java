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
 * RCAgent 意图识别节点：单次 LLM 分类 CHAT（普通知识问答）/ QUERY（查数）/ ATTRIBUTION（归因追问），
 * 并输出时间/维度/指标等要素（供检索与 SQL 规划路由）。
 * <p>
 * P0 骨架：若 LLM 调用失败或返回无法识别文本，默认按 QUERY 处理并降级提示，保证主链路可继续。</p>
 */
@Slf4j
public class RcaIntentNode implements NodeAction {

    private final WorkflowEventBus eventBus;
    private final ChatModel chatModel;

    public RcaIntentNode(WorkflowEventBus eventBus, ChatModel chatModel) {
        this.eventBus = eventBus;
        this.chatModel = chatModel;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String dsId = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.DS_ID, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在识别意图...", "RUNNING");

        String prompt = """
                你是 RCAgent 意图识别器。根据用户的问题文本与数据源类型，将问题分类为以下三类之一：

                - CHAT：普通知识问答（不涉及查数据表，不依赖业务库数据），例如询问概念、口径解释、方法建议
                - QUERY：查数（需要查询业务数据表得到数字结果/明细），例如"本月销售额是多少""华东区订单量"
                - ATTRIBUTION：归因追问（在查数结果基础上进一步追问原因/下钻维度），例如"为什么下降""主要受哪些渠道影响"
                - 补充：询问表的结构/索引/主键/外键/字段定义等 Schema 元数据问题，属于知识问答 → 归为 CHAT（不要判为 QUERY，避免生成 information_schema 查询）

                输出要求（严格单行，禁止多余解释）：
                第一行：CHAT 或 QUERY 或 ATTRIBUTION
                第二行起：用中文一句话说明你的判断依据与识别到的时间/维度/指标要素（没有则写"无"）。

                用户问题：%s
                数据源 ID：%s
                """.formatted(truncateForPrompt(question, 1500), dsId);

        String output = callModel(prompt);
        if (output == null || output.isBlank()) {
            log.warn("RCAgent 意图识别 LLM 输出为空，默认按 QUERY 处理, threadId={}", threadId);
            output = "QUERY\n意图识别失败（LLM 空输出），默认按查数处理。";
        }
        output = RcaNodeUtils.truncate(output, 2000);
        String firstLine = output.strip().split("\\R", 2)[0].strip().toUpperCase();
        String intentType;
        if (firstLine.contains("ATTRIBUTION")) {
            intentType = "ATTRIBUTION";
        } else if (firstLine.contains("CHAT")) {
            intentType = "CHAT";
        } else {
            intentType = "QUERY";
        }
        String intentResult = output;
        log.info("RCAgent 意图识别: type={}, question='{}'", intentType, truncateForPrompt(question, 80));
        // 日志截断（AGENTS.md 5.3）：问题原文不全量落日志
        publish(threadId, RcaWorkflowKeys.INTENT_TYPE, intentType, "RUNNING");
        publish(threadId, RcaWorkflowKeys.INTENT_RESULT, intentResult, "RUNNING");

        return Map.of(
                RcaWorkflowKeys.INTENT_TYPE, intentType,
                RcaWorkflowKeys.INTENT_RESULT, intentResult,
                RcaWorkflowKeys.WORKFLOW_STATUS, "RUNNING",
                RcaWorkflowKeys.WORKFLOW_MESSAGE, "意图识别完成：" + intentType
        );
    }

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
            log.warn("RCAgent 意图识别 LLM 调用失败: {}", RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            return null;
        }
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }

    private static String truncateForPrompt(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...[已截断]";
    }
}
