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
import java.util.regex.Pattern;

/**
 * RCAgent 查数作答节点（QUERY 意图专用）：基于只读执行结果调用 LLM 产出**一句中文结论**。
 * <p>
 * 背景：QUERY 成功路径原先没有自然语言作答环节，结果节点只能把技术明细原样转贴
 * （「【结论】查询已完成，明细如下：」+ 执行结果文本）。本节点补齐作答环节，把结论写入
 * {@link RcaWorkflowKeys#QUERY_ANSWER}，由 {@link RcaResultCheckNode} 的 QUERY 成功分支优先消费。</p>
 * <p>
 * 门禁（全部在本节点内部完成，不改动任何既有条件边）：
 * ① 意图门禁：仅 {@code intent_type == "QUERY"} 作答，CHAT/ATTRIBUTION/空一律 no-op（零 publish、零 State 写入）；
 * ② 结果门禁：{@code query_result} 非空且不含「执行失败/执行降级/未实际执行」才作答
 * （与 {@link RcaResultCheckNode} 的 {@code summaryFailed} 完全同口径）。</p>
 * <p>
 * 降级契约：LLM 调用失败、输出为空或结论命中 SQL 泄漏正则时**不写任何键**（返回 {@code Map.of()}，永不抛异常），
 * 下游结果节点按既有「无结论」文案逐字符回退，保证 QUERY 主链路不因模型抖动而中断。</p>
 */
@Slf4j
public class RcaQueryAnswerNode implements NodeAction {

    /** 结论输出截断上限（避免超长内容撑爆 State/SSE） */
    private static final int QUERY_ANSWER_MAX_LEN = 500;

    /** 入 prompt 的问题截断上限（与 RcaAnswerNode 口径一致） */
    private static final int QUESTION_MAX_LEN = 1500;

    /** 入 prompt 的查询结果截断上限 */
    private static final int RESULT_MAX_LEN = 4000;

    /** 入 prompt 的 SQL 摘要截断上限（仅内部理解统计口径，禁止外显） */
    private static final int SQL_DIGEST_MAX_LEN = 1500;

    /** SQL 泄漏兜底正则：结论中一旦出现 select … from 形态即视为无效结论（安全兜底，AGENTS.md 5.4） */
    private static final Pattern SQL_LEAK_PATTERN =
            Pattern.compile("(?is)\\bselect\\b[\\s\\S]{0,400}?\\bfrom\\b");

    /** 结果失败关键词（与 RcaResultCheckNode.summaryFailed 完全同口径） */
    private static final String[] FAIL_KEYWORDS = {"执行失败", "执行降级", "未实际执行"};

    /** skill 指令缺失时的内联兜底（与 resources/skills/rc/rc-query-answer.md 语义一致） */
    private static final String FALLBACK_INSTRUCTION = """
            你是查数结论生成器。请仅依据【查询结果】用一句话中文结论直接回答【用户问题】。
            - 结论中的数值、条数、维度必须与【查询结果】完全一致，禁止换算、取整或推断；结果为空时如实说明"未查询到符合条件的数据"。
            - 【查询口径】仅供理解统计口径，严禁在结论中复述或引用。
            - 禁止输出 SQL 原文、库名/连接串、账号、密码、Token、表结构表达式（如 COUNT(*)）；禁止复述"风险等级/行数/列"等内部字段名。
            - 只输出结论本身，不要输出标题、编号、Markdown 代码块或任何前后缀。
            """;

    private final WorkflowEventBus eventBus;
    private final ChatModel chatModel;
    private final String skillInstruction;

    public RcaQueryAnswerNode(WorkflowEventBus eventBus, ChatModel chatModel, String skillInstruction) {
        this.eventBus = eventBus;
        this.chatModel = chatModel;
        this.skillInstruction = skillInstruction;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String intentType = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.INTENT_TYPE, "");
        // Q1 意图门禁：非 QUERY 直接 no-op（零 publish、零 State 写入，ATTRIBUTION 链路语义等价）
        if (!"QUERY".equals(intentType)) {
            return Map.of();
        }
        String queryResult = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUERY_RESULT, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        // Q2 结果门禁：无结果不作答，交回既有校验节点逻辑
        if (queryResult.isBlank()) {
            log.debug("RCAgent 查数作答跳过: skip_reason=EMPTY_RESULT, threadId={}", threadId);
            return Map.of();
        }
        // Q3 失败门禁：失败/降级/策略性未执行一律不作答（做 LLM 调用前短路，失败文案零改动）
        for (String keyword : FAIL_KEYWORDS) {
            if (queryResult.contains(keyword)) {
                log.debug("RCAgent 查数作答跳过: skip_reason=FAILED_RESULT, threadId={}", threadId);
                return Map.of();
            }
        }

        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String sqlGenerate = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SQL_GENERATE, "");
        // Q4 运行期横幅（仅出现在 QUERY 成功路径）
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在生成查数结论...", "RUNNING");

        String prompt = buildPrompt(question, sqlGenerate, queryResult);
        String raw = callModel(prompt);
        // Q5 LLM 失败/空输出：不写键，交由结果节点回退既有文案
        if (raw == null || raw.isBlank()) {
            log.warn("RCAgent 查数作答失败: skip_reason=LLM_EMPTY, questionLen={}, resultLen={}, threadId={}",
                    question.length(), queryResult.length(), threadId);
            return Map.of();
        }
        String conclusion = normalize(raw);
        // Q5 归一化后仍可能为空（模型仅输出代码围栏/前缀）：视同空输出降级，不写键、不发完成横幅
        if (conclusion.isBlank()) {
            log.warn("RCAgent 查数作答失败: skip_reason=LLM_EMPTY, answerLen=0, questionLen={}, resultLen={}, threadId={}",
                    question.length(), queryResult.length(), threadId);
            return Map.of();
        }
        // Q5 安全兜底：结论命中 SQL 泄漏正则 → 视为无效结论，不写键（回退现状）
        if (SQL_LEAK_PATTERN.matcher(conclusion).find()) {
            log.warn("RCAgent 查数作答失败: skip_reason=SQL_LEAK, answerLen={}, threadId={}",
                    conclusion.length(), threadId);
            return Map.of();
        }
        // Q6 结论有效：附加 State 键 + 完成横幅（日志仅长度/计数，禁止全文）
        publish(threadId, RcaWorkflowKeys.QUERY_ANSWER, conclusion, "RUNNING");
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "查数结论已生成", "RUNNING");
        log.info("RCAgent 查数作答：answerLen={}, questionLen={}, resultLen={}, threadId={}",
                conclusion.length(), question.length(), queryResult.length(), threadId);
        return Map.of(RcaWorkflowKeys.QUERY_ANSWER, conclusion);
    }

    /**
     * 提示词装配（逐段，顺序不可变）：instruction → 用户问题 → 查询口径（内部）→ 查询结果 → 输出要求。
     * 问题/口径/结果均按既有截断惯例入 prompt（AGENTS.md 5.3：不把超长原文全量落 State/日志）。
     */
    private String buildPrompt(String question, String sqlGenerate, String queryResult) {
        return """
                %s

                【用户问题】
                %s

                【查询口径（内部字段，仅供理解统计口径，严禁在结论中复述或引用）】
                %s

                【查询结果（技术明细，含风险等级/行数/列/行数据）】
                %s

                【输出要求】
                - 只输出一句话中文结论（≤100 字），必须是完整句子，直接回答用户所问；不要输出任何前后缀、标题、编号、Markdown 代码块。
                - 结论中的数值、条数、维度必须与【查询结果】完全一致；禁止换算、取整、推断或补充结果中不存在的信息；结果为空时如实说明「未查询到符合条件的数据」。
                - 禁止在结论中出现 SQL 原文、库名/连接串、账号、密码、Token、表结构表达式（如 COUNT(*)）；禁止复述"风险等级/行数/列"等内部字段名。
                - 表名可适度口语化（如 workflow_metadata 可直接使用原名），但不得编造表名或列名。
                """.formatted(getInstruction(),
                RcaNodeUtils.truncate(question, QUESTION_MAX_LEN),
                sqlGenerate.isBlank() ? "（无）" : RcaNodeUtils.truncate(sqlGenerate, SQL_DIGEST_MAX_LEN),
                queryResult.isBlank() ? "（无）" : RcaNodeUtils.truncate(queryResult, RESULT_MAX_LEN));
    }

    /** skill 指令（缺失时用内联兜底），保证提示词始终可用 */
    private String getInstruction() {
        return (skillInstruction == null || skillInstruction.isBlank()) ? FALLBACK_INSTRUCTION : skillInstruction;
    }

    /**
     * 结论归一化（逐条）：strip → 去代码围栏行 → 去模型自加前缀（【结论】/结论：/答：）→ 折叠连续空行 → truncate(500)。
     */
    private static String normalize(String raw) {
        String text = raw.strip();
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        text = sb.toString().strip();
        text = stripPrefix(text, "【结论】");
        text = stripPrefix(text, "结论：");
        text = stripPrefix(text, "答：");
        text = text.replaceAll("(?:\\r?\\n[ \\t]*){2,}", "\n").strip();
        return RcaNodeUtils.truncate(text, QUERY_ANSWER_MAX_LEN);
    }

    /** 去除模型自加的前缀（可叠加），避免与结果节点的「【结论】」前缀重复 */
    private static String stripPrefix(String text, String prefix) {
        String t = text.strip();
        while (t.startsWith(prefix)) {
            t = t.substring(prefix.length()).strip();
        }
        return t;
    }

    /** 与 RcaAnswerNode 同款调用写法：流式优先，异常吞掉返回 null 交由上层降级（永不外抛） */
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
            log.warn("RCAgent 查数作答节点 LLM 调用失败: {}",
                    RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            return null;
        }
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
