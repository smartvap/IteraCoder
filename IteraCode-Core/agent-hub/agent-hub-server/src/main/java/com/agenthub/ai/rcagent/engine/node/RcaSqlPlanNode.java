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
 * RCAgent SQL 规划节点：单次 LLM 将用户问题拆分为可执行的查询目标
 * （主表/时间范围/分组维度/筛选条件/聚合方式），供 SQL 生成 Agent 使用。
 */
@Slf4j
public class RcaSqlPlanNode implements NodeAction {

    private final WorkflowEventBus eventBus;
    private final ChatModel chatModel;

    public RcaSqlPlanNode(WorkflowEventBus eventBus, ChatModel chatModel) {
        this.eventBus = eventBus;
        this.chatModel = chatModel;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String retrievalContext = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RETRIEVAL_CONTEXT, "");
        String dsId = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.DS_ID, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在规划查询目标...", "RUNNING");

        String prompt = """
                你是查数 SQL 规划器。根据用户问题与检索到的口径/表结构上下文，输出 SQL 规划（不要写完整 SQL，只输出规划）。

                输出格式（Markdown）：
                - **查询目标**：一句话说明要查询什么
                - **候选主表**：表名与依据
                - **时间范围**：有则给出起止条件；无则写"未指定"
                - **分组维度**：如需按维度拆解，列出候选列；不需要写"无"
                - **筛选条件**：where 候选条件列表
                - **聚合方式**：SUM/COUNT/AVG 等及目标列
                - **风险提示**：涉及敏感表/字段、大表全扫、SELECT * 等，必须如实标注

                用户问题：%s
                数据源 ID：%s
                %s
                """.formatted(truncate(question, 1500), dsId,
                retrievalContext.isBlank() ? "" : "\n【检索上下文】\n" + truncate(retrievalContext, 4000));

        String plan = callModel(prompt);
        if (plan == null || plan.isBlank()) {
            log.warn("RCAgent SQL 规划 LLM 输出为空，使用降级规划, threadId={}", threadId);
            plan = "- **查询目标**：按问题直接查数（LLM 规划失败降级）\n- **风险提示**：规划失败，SQL 需经验证分级后执行";
        }
        plan = RcaNodeUtils.truncate(plan, 4000);
        publish(threadId, RcaWorkflowKeys.SQL_PLAN, plan, "RUNNING");
        return Map.of(RcaWorkflowKeys.SQL_PLAN, plan);
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
            log.warn("RCAgent SQL 规划 LLM 调用失败: {}", RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            return null;
        }
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...[已截断]";
    }
}
