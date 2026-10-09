package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.tool.RcaSqlGuardTools;
import com.agenthub.ai.rcagent.sqlguard.normalize.NormalizedSqlDraft;
import com.agenthub.ai.rcagent.sqlguard.normalize.RcaSqlDraftEntity;
import com.agenthub.ai.rcagent.sqlguard.normalize.SqlDraftNormalizer;
import com.agenthub.ai.rcagent.sqlguard.normalize.SqlOutputCapability;
import com.agenthub.ai.rcagent.sqlguard.normalize.SqlOutputIssue;
import com.agenthub.ai.rcagent.sqlguard.parser.JsSqlParserAdapter;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RCAgent SQL 生成节点：多轮 Agent 循环生成只读 SQL 草案（平行 MultiRoundAgentNode 风格）。
 * <p>
 * 语义要点（FR-BE-005）：
 * <ul>
 *   <li>仅生成 SELECT 只读查询；严禁 DML/DDL（BLOCK 由后续验证节点拦截）</li>
 *   <li>方言随数据源（默认 MySQL；Oracle/OceanBase 由 module-002 对齐后扩展）</li>
 *   <li>生成后进入 Schema 预检回流自修正：{@link RcaSqlGuardTools#schemaCheck} 预检结果
 *       作为反馈注入 LLM 重新生成（工具循环 self-fix 的 P0 近似），修正轮有限次防发散</li>
 *   <li>审核打回（resume SENT_BACK）恢复执行时，本节点生成完成即把 workflow_status 从 SENT_BACK 回写 RUNNING
 *       （SENT_BACK 为 resume 后短暂可观测状态，修复审查问题 3）</li>
 * </ul>
 * <b>偏离说明（修复审查问题 4）</b>：P0 未直接实例化 workflow 存量 MultiRoundAgentNode/ReactAgent 完整工具循环
 * （保持图节点骨架 + 自写多轮流式），而是用「schemaCheck 预检 → 结果回流 → 重生成」近似工具循环 self-fix，
 * 并仅复用其 THREAD_ID_KEY/SSE 推送约定；后续迭代可将 schemaCheck/schema 字典注册为 ReactAgent 函数工具闭环。
 * <br>
 * 安全约束（AGENTS.md 5.4 / api-contract 事件安全约束）：SQL 草案全文不进入 SSE（不推送 sql_generate 增量/全量），
 * 仅落 State/checkpoint，经审核通道（review_content/审核审计）可见；日志只记录长度。
 *
 * <p><b>输出契约改造（rca1.1.0.0）</b>：提取调用点由 {@code RcaSqlExtractor.extract} 替换为
 * {@link SqlDraftNormalizer#normalize(String, DbType)}（分层 S0~S5）；新增 L1 结构化输出能力探测与
 * {@code STRUCTURED_JSON_SCHEMA → TOOL_CALLING → TEXT_ONLY} 降级链；新增 L4 输出格式修复回流
 * （{@link #OUTPUT_FIX_MAX_ROUNDS}=1，回喂仅含 issue 分类与中性提示，严禁拼接 SQL 全文）。
 * {@code sql_generate} 语义收敛为「纯 SQL 或空串」。</p>
 *
 * <p><b>回流耗尽终态（2026-09-15 修订：回流耗尽终态裁定）</b>：全部生成轮 + L4 回流耗尽后仍无可解析只读 SQL 时，
 * {@code sql_generate} 写入<b>空串</b>（不写占位文案），使下游 {@code RcaValidateNode} 命中既有
 * {@code sql.isBlank()} 分支 → {@code risk_level=MEDIUM} → {@code WAITING_REVIEW} 转人工，
 * <b>不产生</b> {@code risk_level=BLOCK}（FR-BE-011/SC-003/验收场景 8）。失败原因改经
 * {@code workflow_message}（SSE）以中性文案输出；{@link #DEFAULT_SQL_PLACEHOLDER} 仅作消息文案使用，
 * <b>不再写入 state</b>。埋点补充 {@code modelName}/{@code capability}/{@code costMs}（FR-BE-016/SC-009）。</p>
 */
@Slf4j
public class RcaSqlGenerateNode implements NodeAction {

    private final WorkflowEventBus eventBus;
    private final ChatModel chatModel;
    private final String instruction;
    private final int maxRounds;
    private final RcaSqlGuardTools sqlGuardTools;
    private final SqlDraftNormalizer sqlDraftNormalizer;

    /** {@code sql-generate} 角色实际模型标识（L5 埋点字段，FR-BE-016/SC-009；缺省回退 ChatModel 实现类简名） */
    private final String modelName;

    /** Schema 预检不通过时的回流修正最大轮数（防发散；P0 近似工具循环 self-fix） */
    private static final int SCHEMA_FIX_MAX_ROUNDS = 2;

    /** 单轮 LLM 输出为空（null/空白）时的重试上限（FR-BE-005）；不改变 maxRounds 总轮数语义 */
    private static final int EMPTY_OUTPUT_MAX_RETRY = 1;

    /** L4「输出格式修复回流」最大轮数（FR-BE-010，限轮防发散） */
    private static final int OUTPUT_FIX_MAX_ROUNDS = 1;

    /**
     * 全部轮次（含 L4 回流）耗尽后的中性失败文案（逐字符保持不变）：
     * 仅作为 {@code workflow_message}（SSE）的人类可读失败原因，<b>不再写入 {@code sql_generate}</b>
     * （该键只承载纯 SQL 或空串）。2026-09-15 修订：回流耗尽终态裁定。
     */
    private static final String DEFAULT_SQL_PLACEHOLDER = "-- SQL 生成失败（模型空输出），请人工检查后重试";

    /** 默认归一组件（仅用于保持既有 5 参构造器兼容；生产装配由 {@code RcaWorkflowGraphConfig} 注入 Bean） */
    private static final SqlDraftNormalizer DEFAULT_NORMALIZER = new SqlDraftNormalizer(new JsSqlParserAdapter());

    /** L1 能力探测结果缓存（进程内，按模型标识；默认缺省视为 TEXT_ONLY 安全默认，FR-BE-008） */
    private final Map<String, SqlOutputCapability> capabilityCache = new ConcurrentHashMap<>();

    /**
     * 兼容构造器（保留既有 5 参签名，供存量调用方 / 单测使用）：不注入 normalizer / modelName 时使用默认实现。
     */
    public RcaSqlGenerateNode(WorkflowEventBus eventBus, ChatModel chatModel,
            String instruction, int maxRounds, RcaSqlGuardTools sqlGuardTools) {
        this(eventBus, chatModel, instruction, maxRounds, sqlGuardTools, DEFAULT_NORMALIZER, null);
    }

    /**
     * 兼容构造器（保留既有 6 参签名，供 T008 早前调用方 / 单测使用）。
     */
    public RcaSqlGenerateNode(WorkflowEventBus eventBus, ChatModel chatModel, String instruction, int maxRounds,
            RcaSqlGuardTools sqlGuardTools, SqlDraftNormalizer sqlDraftNormalizer) {
        this(eventBus, chatModel, instruction, maxRounds, sqlGuardTools, sqlDraftNormalizer, null);
    }

    /**
     * 完整构造器：额外注入 {@code sql-generate} 角色模型标识（L5 埋点用，FR-BE-016/SC-009）。
     * 未提供时回退 {@link ChatModel} 实现类简名，不阻塞装配。
     */
    public RcaSqlGenerateNode(WorkflowEventBus eventBus, ChatModel chatModel, String instruction, int maxRounds,
            RcaSqlGuardTools sqlGuardTools, SqlDraftNormalizer sqlDraftNormalizer, String modelName) {
        this.eventBus = eventBus;
        this.chatModel = chatModel;
        this.instruction = instruction;
        this.maxRounds = Math.max(1, maxRounds);
        this.sqlGuardTools = sqlGuardTools;
        this.sqlDraftNormalizer = sqlDraftNormalizer == null ? DEFAULT_NORMALIZER : sqlDraftNormalizer;
        this.modelName = (modelName == null || modelName.isBlank())
                ? (chatModel == null ? "" : chatModel.getClass().getSimpleName())
                : modelName;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String dsId = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.DS_ID, "");
        String sqlPlan = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SQL_PLAN, "");
        String retrievalContext = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RETRIEVAL_CONTEXT, "");
        String reviewFeedback = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.REVIEW_FEEDBACK, "");
        String workflowStatus = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.WORKFLOW_STATUS, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在生成 SQL（方言随数据源，只读校验）...", "RUNNING");

        String round1Instruction = instruction + """
                

                【当前任务数据】
                用户问题：%s
                数据源 ID：%s
                SQL 规划：
                %s
                检索上下文（表/字段/口径）：
                %s
                %s
                """.formatted(truncate(question, 1500), dsId,
                truncate(sqlPlan, 2000), truncate(retrievalContext, 5000),
                reviewFeedback.isBlank() ? "" : "【人工审核意见】\n" + truncate(reviewFeedback, 1000));

        // 多轮生成：每轮经 SqlDraftNormalizer 归一（分层 S0~S5）+ 失败分类，sqlDraft 取「最后一轮有效（issue=NONE）SQL」；
        // 末轮无效（空/围栏/语法错误等）时沿用上一轮有效结果，不再拼接多轮原文
        // （围栏 + 分号会使 sqlguard 将草案误判为多语句 MULTI 而误 BLOCK）。
        // dbType：生成节点暂不解析数据源方言（无可靠来源），传 null 由 normalizer 按 MYSQL 兜底（不失败）。
        DbType dbType = null;
        String sqlDraft = "";
        String prevRawOutput = null;
        String lastNotReadonlyDraft = null;
        SqlOutputIssue lastIssue = SqlOutputIssue.EMPTY;
        for (int round = 1; round <= this.maxRounds; round++) {
            String roundPrompt = round == 1 ? round1Instruction : """
                    %s

                    第 %d/%d 轮。上一轮输出：
                    %s

                    请继续推进：自查 SQL 是否只读、语法正确、字段在上下文中存在；如有问题输出修正后的完整 SQL。
                    """.formatted(round1Instruction, round, this.maxRounds, truncate(prevRawOutput, 4000));
            NormalizedSqlDraft draft;
            long startNanos = System.nanoTime();
            // L1：结构化输出优先（能力探测 + 降级链），不可用时回退既有纯文本 streamCall
            RcaSqlDraftEntity structured = structuredDraftCall(roundPrompt);
            if (structured != null) {
                draft = sqlDraftNormalizer.normalize(structured, dbType);
            } else {
                String response = streamCallWithEmptyRetry(roundPrompt, state);
                if (response == null) {
                    // 本轮重试后仍为空输出：本轮无有效结果，沿用上一轮已提取的草案（FR-BE-005）
                    draft = NormalizedSqlDraft.of("", SqlOutputIssue.EMPTY, SqlDraftNormalizer.LAYER_NONE, 0);
                } else {
                    prevRawOutput = response;
                    draft = sqlDraftNormalizer.normalize(response, dbType);
                }
            }
            long costMs = elapsedMs(startNanos);
            lastIssue = draft.getIssue();
            logAttempt(threadId, "generate#" + round, draft, currentCapability(), costMs);
            if (draft.isUsable()) {
                sqlDraft = draft.getSql();
            } else if (draft.getIssue() == SqlOutputIssue.NOT_READONLY && !draft.getSql().isBlank()) {
                // 写形态不放行归一：记录原文，仅在最终无任何有效只读草案时交 sqlguard 按既有策略 BLOCK
                lastNotReadonlyDraft = draft.getSql();
            }
        }

        // L4 输出格式修复回流（FR-BE-010/011）：全部生成轮后仍无有效草案（sqlDraft 为空）且失败分类属可重生成类时，
        // 回喂「结构化错误」重生成，限 1 轮（OUTPUT_FIX_MAX_ROUNDS，防发散、避免放大 token）；
        // 提示仅含 issue 分类与中性提示，严禁拼接 SQL 全文/原始输出；回流失败不得直接置 risk_level=BLOCK。
        // EMPTY 亦纳入本次回流（FR-BE-010 可重生成集合），此前生成轮内 EMPTY_OUTPUT_MAX_RETRY=1 已完成一次重试；
        // sqlDraft.isBlank() 前置条件避免已有有效草案时额外回流并覆盖既有有效 SQL（api-contract 5.4 草案取值规则）。
        int outputFixRound = 0;
        while (outputFixRound < OUTPUT_FIX_MAX_ROUNDS && sqlDraft.isBlank()
                && lastIssue.isOutputFixCandidate()) {
            outputFixRound++;
            // SSE 文案不含 SqlOutputIssue 枚举名（FR-BE-007：分类禁止作为 SSE 事件值，仅落应用日志）
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                    "SQL 输出格式修复：第 " + outputFixRound + " 轮，正在重新生成", "RUNNING");
            String fixPrompt = round1Instruction + """

                    上一轮生成结果未能解析为可执行的只读单语句 SQL。
                    问题分类：%s
                    %s
                    请仅输出一条完整的、语法正确的只读 SELECT 语句；禁止 Markdown 代码围栏、禁止说明文字、禁止 DML/DDL。
                    """.formatted(lastIssue.name(), outputFixHint(lastIssue));
            NormalizedSqlDraft fixDraft;
            long startNanos = System.nanoTime();
            RcaSqlDraftEntity structuredFix = structuredDraftCall(fixPrompt);
            if (structuredFix != null) {
                fixDraft = sqlDraftNormalizer.normalize(structuredFix, dbType);
            } else {
                String fixResponse = streamCall(fixPrompt, state);
                fixDraft = (fixResponse == null || fixResponse.isBlank())
                        ? NormalizedSqlDraft.of("", SqlOutputIssue.EMPTY, SqlDraftNormalizer.LAYER_NONE, 0)
                        : sqlDraftNormalizer.normalize(fixResponse, dbType);
            }
            long costMs = elapsedMs(startNanos);
            lastIssue = fixDraft.getIssue();
            logAttempt(threadId, "output-fix#" + outputFixRound, fixDraft, currentCapability(), costMs);
            if (fixDraft.isUsable()) {
                sqlDraft = fixDraft.getSql();
                break;
            }
            if (fixDraft.getIssue() == SqlOutputIssue.NOT_READONLY && !fixDraft.getSql().isBlank()) {
                lastNotReadonlyDraft = fixDraft.getSql();
            }
        }

        if (sqlDraft.isBlank() && lastNotReadonlyDraft != null) {
            // 无有效只读草案但存在写形态输出：原样交 sqlguard 判级，由既有策略 BLOCK（安全策略不放宽，DC-04）
            sqlDraft = lastNotReadonlyDraft;
        }
        if (sqlDraft.isBlank()) {
            // 全部轮次（含 L4 回流）仍无可解析只读 SQL：sql_generate 保持空串（只承载纯 SQL 或空串），
            // 失败原因走 workflow_message 中性文案（不含 SQL 全文/原始输出）；下游 RcaValidateNode
            // 命中「SQL 为空」分支 → risk_level=MEDIUM → WAITING_REVIEW 转人工，不产生 BLOCK
            // （2026-09-15 修订：回流耗尽终态裁定，FR-BE-011/SC-003）。
            log.warn("RCAgent SQL 生成全部轮次无可提取结果，threadId={}", threadId);
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, DEFAULT_SQL_PLACEHOLDER, "RUNNING");
        }
        sqlDraft = RcaNodeUtils.truncate(sqlDraft, 20000);

        // Schema 预检回流自修正（近似工具循环 self-fix，修复审查问题 4）：
        // 预检发现表/字段不存在时把预检报告作为反馈注入 LLM 重新生成，最多 SCHEMA_FIX_MAX_ROUNDS 轮；
        // 仅回流修正不拦截，最终风险分级仍由 RcaValidateNode 完成。
        int fixRound = 0;
        while (fixRound < SCHEMA_FIX_MAX_ROUNDS && sqlGuardTools != null && sqlGuardTools.available()) {
            String checkReport;
            try {
                checkReport = sqlGuardTools.schemaCheck(dsId, sqlDraft);
            } catch (Exception e) {
                log.warn("RCAgent Schema 预检异常(忽略，不阻塞主链路): {}",
                        RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
                break;
            }
            if (checkReport == null || checkReport.isBlank() || !needsSchemaFix(checkReport)) {
                if (checkReport != null && !checkReport.isBlank()) {
                    publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                            "Schema 预检通过，进入验证与分级", "RUNNING");
                }
                break;
            }
            fixRound++;
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                    "Schema 预检发现表/字段问题，第 " + fixRound + " 轮修正中...", "RUNNING");
            String fixPrompt = round1Instruction + """

                    第 %d 轮 Schema 预检未通过（表/字段缺失等）。请严格基于以下预检反馈修正 SQL：
                    待修正 SQL：
                    %s

                    【Schema 预检反馈】
                    %s

                    要求：仅输出修正后的完整只读 SELECT SQL（保持方言与只读约束，严禁 DML/DDL）；如无需修改请原样输出。
                    """.formatted(fixRound, truncate(sqlDraft, 8000), truncate(checkReport, 2000));
            long startNanos = System.nanoTime();
            String corrected = streamCall(fixPrompt, state);
            long costMs = elapsedMs(startNanos);
            if (corrected == null || corrected.isBlank()) {
                break;
            }
            // 修正轮输出同样先经 SqlDraftNormalizer 归一（去围栏/去尾分号/分类）再进入下一轮 schemaCheck（FR-BE-004）
            NormalizedSqlDraft correctedDraft = sqlDraftNormalizer.normalize(corrected, dbType);
            logAttempt(threadId, "schema-fix#" + fixRound, correctedDraft, currentCapability(), costMs);
            if (!correctedDraft.isUsable()) {
                // 修正轮无有效 SQL：保留上一轮已归一的草案，避免把围栏原文送入预检/验证
                break;
            }
            sqlDraft = RcaNodeUtils.truncate(correctedDraft.getSql(), 20000);
        }

        // 审核打回恢复执行（resume SENT_BACK）：生成完成即把 SENT_BACK 回写 RUNNING
        // （SENT_BACK 为 resume 后短暂可观测状态，修复审查问题 3）
        boolean resentFromSentBack = "SENT_BACK".equals(workflowStatus);
        log.info("RCAgent SQL 生成完成: threadId={}, length={}, resentFromSentBack={}",
                threadId, sqlDraft.length(), resentFromSentBack);
        // SQL 草案全文不推 SSE（仅审核通道/state/审核审计可见，修复审查问题 1；AGENTS.md 5.4）
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                "SQL 草案已生成（" + sqlDraft.length() + " 字符），进入验证与分级（草案仅审核通道可见）", "RUNNING");

        Map<String, Object> result = new HashMap<>();
        result.put(RcaWorkflowKeys.SQL_GENERATE, sqlDraft);
        if (resentFromSentBack) {
            result.put(RcaWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
            result.put(RcaWorkflowKeys.WORKFLOW_MESSAGE, "已按审核意见重新生成 SQL，继续执行");
        }
        return result;
    }

    /**
     * 单轮 LLM 调用 + 空输出有限重试（FR-BE-005）：
     * 输出为 null/空白时按同一提示重试该轮，上限 {@link #EMPTY_OUTPUT_MAX_RETRY} 次；
     * 重试后仍为空返回 null（由调用方沿用上一轮有效结果），不改变 maxRounds 总轮数语义。
     */
    private String streamCallWithEmptyRetry(String prompt, OverAllState state) {
        String output = streamCall(prompt, state);
        int retry = 0;
        while ((output == null || output.isBlank()) && retry < EMPTY_OUTPUT_MAX_RETRY) {
            retry++;
            log.info("RCAgent SQL 生成单轮空输出，重试 {}/{}", retry, EMPTY_OUTPUT_MAX_RETRY);
            output = streamCall(prompt, state);
        }
        return (output == null || output.isBlank()) ? null : output;
    }

    private String streamCall(String prompt, OverAllState state) {
        try {
            StringBuilder sb = new StringBuilder();
            if (chatModel instanceof StreamingChatModel) {
                StreamingChatModel streaming = (StreamingChatModel) chatModel;
                streaming.stream(new Prompt(new UserMessage(prompt)))
                        .doOnNext(chunk -> {
                            String text = chunk.getResult() != null && chunk.getResult().getOutput() != null
                                    ? chunk.getResult().getOutput().getText() : "";
                            if (text != null && !text.isEmpty()) {
                                // 仅本地累积，不把 SQL 草案增量推 SSE（审核敏感字段，修复审查问题 1）
                                sb.append(text);
                            }
                        })
                        .blockLast();
            } else {
                String result = chatModel.call(new Prompt(new UserMessage(prompt)))
                        .getResult().getOutput().getText();
                if (result != null) sb.append(result);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("RCAgent SQL 生成 LLM 调用失败: {}", RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            return null;
        }
    }

    /**
     * L1 结构化输出请求（能力探测 + 降级链，FR-BE-008/009）：
     * {@code STRUCTURED_JSON_SCHEMA}（{@code ChatClient.entity}）→ {@code TOOL_CALLING}（提交草案工具）
     * → {@code TEXT_ONLY}（返回 null，由调用方回退既有 {@link #streamCall}）。
     *
     * <p>任一环节失败均不得使节点失败；探测结果按模型标识缓存（进程内），未探测/探测失败一律安全降级
     * {@code TEXT_ONLY}。结构化输出走阻塞 {@code call()}（Spring AI 结构化输出与流式组合的可用性未识别，
     * 故与流式分支并存）。</p>
     */
    private RcaSqlDraftEntity structuredDraftCall(String prompt) {
        if (!(chatModel instanceof ChatModel)) {
            return null;
        }
        String cacheKey = chatModel.getClass().getName();
        SqlOutputCapability capability = capabilityCache.get(cacheKey);
        if (capability == SqlOutputCapability.TEXT_ONLY) {
            return null;
        }
        try {
            if (capability == null || capability == SqlOutputCapability.STRUCTURED_JSON_SCHEMA) {
                try {
                    ChatClient client = ChatClient.builder((ChatModel) chatModel).build();
                    RcaSqlDraftEntity entity = client.prompt().user(prompt).call()
                            .entity(RcaSqlDraftEntity.class);
                    if (entity != null && entity.getSql() != null && !entity.getSql().isBlank()) {
                        capabilityCache.put(cacheKey, SqlOutputCapability.STRUCTURED_JSON_SCHEMA);
                        return entity;
                    }
                } catch (Exception e) {
                    log.info("RCAgent 结构化输出不可用，继续降级: {}", e.getClass().getSimpleName());
                }
            }
            // TOOL_CALLING：以「提交 SQL 草案」工具名请求结构化参数
            SqlDraftToolCapture capture = new SqlDraftToolCapture();
            ChatClient client = ChatClient.builder((ChatModel) chatModel).build();
            client.prompt().user(prompt).tools(capture).call().content();
            RcaSqlDraftEntity captured = capture.get();
            if (captured != null && captured.getSql() != null && !captured.getSql().isBlank()) {
                capabilityCache.put(cacheKey, SqlOutputCapability.TOOL_CALLING);
                return captured;
            }
            capabilityCache.put(cacheKey, SqlOutputCapability.TEXT_ONLY);
            return null;
        } catch (Exception e) {
            capabilityCache.put(cacheKey, SqlOutputCapability.TEXT_ONLY);
            log.info("RCAgent 结构化/工具调用均不可用，降级 TEXT_ONLY: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * L5 埋点（脱敏）：记录 threadId / 轮次 / modelName / capability / 分类 / 层码 / 原始输出长度 / 耗时，
     * 严禁 SQL 全文、原始输出、提示词、Token/密钥（FR-BE-016 / SC-009 / AGENTS.md 5.4）。
     */
    private void logAttempt(String threadId, String phase, NormalizedSqlDraft draft,
            SqlOutputCapability capability, long costMs) {
        log.info("RCAgent SQL 输出埋点: threadId={}, phase={}, modelName={}, capability={}, issue={}, layer={}, rawLength={}, costMs={}",
                threadId, phase, modelName, capability, draft.getIssue(), draft.getExtractLayer(),
                draft.getRawLength(), costMs);
    }

    /** 读取当前模型的能力探测结果（未探测/失败回退 TEXT_ONLY 安全默认，FR-BE-008） */
    private SqlOutputCapability currentCapability() {
        if (chatModel == null) {
            return SqlOutputCapability.safeDefault();
        }
        return capabilityCache.getOrDefault(chatModel.getClass().getName(), SqlOutputCapability.safeDefault());
    }

    /** 单轮 LLM 调用耗时（毫秒），仅用于脱敏埋点（FR-BE-016） */
    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 输出修复回流的中性化提示：仅描述问题类别，不含 SQL 全文/原始输出（FR-BE-010） */
    private static String outputFixHint(SqlOutputIssue issue) {
        return switch (issue) {
            case EMPTY -> "上一轮模型输出为空。";
            case NOT_SQL -> "上一轮输出不是可执行的只读 SQL。";
            case SYNTAX_ERROR -> "上一轮输出疑似 SQL 但语法无法解析。";
            case MULTI -> "上一轮输出包含多条语句，需要单条语句。";
            default -> "上一轮输出无法作为只读单语句 SQL。";
        };
    }

    /**
     * tool calling 参数捕获器：承接模型通过工具调用返回的结构化 SQL 草案
     * （进程内单次调用载体，无共享状态；SQL 全文不落日志/不入 SSE）。
     */
    private static final class SqlDraftToolCapture {

        private final AtomicReference<RcaSqlDraftEntity> captured = new AtomicReference<>();

        @Tool(description = "提交生成的只读 SQL 草案（含 sql 与 rationale 两个参数）")
        public String submitSqlDraft(String sql, String rationale) {
            RcaSqlDraftEntity entity = new RcaSqlDraftEntity();
            entity.setSql(sql);
            entity.setRationale(rationale);
            captured.set(entity);
            return "已接收";
        }

        private RcaSqlDraftEntity get() {
            return captured.get();
        }
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }

    /** Schema 预检报告含失败项（passed=false / exists=false）时触发回流修正 */
    private static boolean needsSchemaFix(String report) {
        return report.contains("passed=false") || report.contains("exists=false");
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...[已截断]";
    }
}
