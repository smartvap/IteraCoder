package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.crosssource.CrossSourceConstants;
import com.agenthub.ai.rcagent.crosssource.CrossSourcePlanValidator;
import com.agenthub.ai.rcagent.crosssource.CrossSourceSqlGuardAdapter;
import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;
import com.agenthub.ai.rcagent.crosssource.model.SourceQuerySql;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.service.RcaWorkflowService;
import com.agenthub.ai.rcagent.sqlguard.normalize.NormalizedSqlDraft;
import com.agenthub.ai.rcagent.sqlguard.normalize.SqlDraftNormalizer;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RCAgent 逐源 SQL 生成节点（module-003，新增）。
 *
 * <p>分支契约（api-contract §4.3.1 G0~G6）：按 join 计划**逐源一次 LLM** 生成只读 SELECT，
 * 输出经既有 {@link SqlDraftNormalizer} 归一（去围栏/去尾分号/写形态分类），再经
 * {@link CrossSourceSqlGuardAdapter} 复用既有 schema 预检做**回流修正 ≤1 轮**，
 * 最终组装 {@code source_query_plans}（源 → SQL + 行数上限）写入 State 内部键。</p>
 *
 * <p><b>安全</b>：SQL 草案仅为内部键 / 审核通道可见，**不 publish 任何含 SQL 的键**，
 * 日志只记长度/计数/源序号。任一无有效只读 SQL → 明确 FAILED（禁止用部分结果冒充成功）。</p>
 */
@Slf4j
public class RcaCrossSourceGenerateNode implements NodeAction {

    /** schema 预检回流最大轮数（防发散） */
    private static final int SCHEMA_FIX_MAX_ROUNDS = 1;
    /** 提示词内问题/检索上下文截断长度 */
    private static final int PROMPT_QUESTION_MAX_LEN = 1500;
    private static final int PROMPT_RETRIEVAL_MAX_LEN = 4000;
    /** 计划片段截断长度 */
    private static final int PROMPT_PLAN_MAX_LEN = 1500;

    private final WorkflowEventBus eventBus;
    private final ChatModel chatModel;
    private final String instruction;
    private final SqlDraftNormalizer sqlDraftNormalizer;
    private final CrossSourceSqlGuardAdapter guardAdapter;
    private final CrossSourceProperties properties;

    public RcaCrossSourceGenerateNode(WorkflowEventBus eventBus, ChatModel chatModel, String skillInstruction,
            SqlDraftNormalizer sqlDraftNormalizer, CrossSourceSqlGuardAdapter guardAdapter,
            CrossSourceProperties properties) {
        this.eventBus = eventBus;
        this.chatModel = chatModel;
        this.instruction = skillInstruction;
        this.sqlDraftNormalizer = sqlDraftNormalizer;
        this.guardAdapter = guardAdapter;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String threadId = threadId(state);
        String joinPlanText = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.JOIN_PLAN, "");
        // G1：计划缺失（理论上由条件边保证不达）
        if (joinPlanText.isBlank()) {
            return Map.of();
        }
        String workflowStatus = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.WORKFLOW_STATUS, "");
        CrossSourceJoinPlan plan = parsePlan(joinPlanText);
        if (plan == null || !plan.isExecutable()) {
            if ("FAILED".equals(workflowStatus)) {
                // 规划节点已判 FAILED（用户指定键不可用）：保持既有诊断，不再生成任何 SQL
                log.info("RCAgent 跨源生成：计划不可执行且会话已 FAILED，跳过生成（保留规划诊断）");
                return Map.of();
            }
            return failure(threadId, 1, CrossSourceConstants.FAIL_PLAN_MISSING,
                    CrossSourceConstants.FAIL_PLAN_MISSING);
        }
        CrossSourceProperties props = effectiveProperties();
        List<String> dsIds = com.agenthub.ai.rcagent.crosssource.support.CrossSourceStateSupport.dsIds(state);
        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String retrievalContext = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RETRIEVAL_CONTEXT, "");
        String reviewFeedback = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.REVIEW_FEEDBACK, "");

        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在逐源生成只读查询草案...", "RUNNING");

        boolean regenerated = "SENT_BACK".equals(workflowStatus);
        List<SourceQuerySql> sqls = new ArrayList<>();
        List<int[]> sides = List.of(new int[]{plan.getLeftSourceIndex(), 0}, new int[]{plan.getRightSourceIndex(), 1});
        for (int[] side : sides) {
            int sourceIndex = side[0];
            boolean leftSide = side[1] == 0;
            String dsId = leftSide ? plan.getLeftDsId() : plan.getRightDsId();
            if (dsId == null || dsId.isBlank()) {
                dsId = sourceIndex >= 1 && sourceIndex <= dsIds.size() ? dsIds.get(sourceIndex - 1) : "";
            }
            GeneratedSource generated = generateOne(plan, leftSide, sourceIndex, dsId, question,
                    retrievalContext, reviewFeedback, threadId);
            if (!generated.valid()) {
                // G4：某源无有效只读 SQL → 明确 FAILED（不使用部分结果）
                log.warn("RCAgent 跨源生成失败: sourceIndex={}, state={}", sourceIndex, generated.state());
                return failure(threadId, sourceIndex,
                        CrossSourceConstants.FAIL_SQL_MISSING_PREFIX + sourceIndex
                                + CrossSourceConstants.FAIL_SQL_MISSING_SUFFIX,
                        CrossSourceConstants.FAIL_GENERATE_PREFIX + sourceIndex
                                + CrossSourceConstants.FAIL_GENERATE_SUFFIX);
            }
            SourceQuerySql sql = new SourceQuerySql();
            sql.setThreadId(threadId);
            sql.setSourceIndex(sourceIndex);
            sql.setDsId(dsId);
            sql.setSourceRole(leftSide ? CrossSourceConstants.ROLE_PROBE : CrossSourceConstants.ROLE_TARGET);
            sql.setSqlText(CrossSourceConstants.truncate(generated.sql(), props.getSourceSqlMaxLen()));
            sql.setRowLimit(props.getPerSourceRowLimit());
            sql.setStatus(regenerated ? CrossSourceConstants.SQL_STATUS_REGENERATED
                    : CrossSourceConstants.SQL_STATUS_READY);
            sqls.add(sql);
        }

        String plansJson = JSON.toJSONString(sqls);
        log.info("RCAgent 跨源逐源 SQL 生成完成: sourceCount={}, totalSqlLength={}, regenerated={}",
                sqls.size(), sqls.stream().mapToInt(s -> s.getSqlText() == null ? 0 : s.getSqlText().length()).sum(),
                regenerated);
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                "已生成 " + sqls.size() + " 条只读查询草案，进入逐源验证（草案仅审核通道可见）", "RUNNING");

        Map<String, Object> result = new HashMap<>();
        result.put(RcaWorkflowKeys.SOURCE_QUERY_PLANS, plansJson);
        if (regenerated) {
            // G6：审核打回重生成完成后回写 RUNNING（与既有 RcaSqlGenerateNode 同款语义）
            result.put(RcaWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
            result.put(RcaWorkflowKeys.WORKFLOW_MESSAGE, "已按审核意见重新生成跨源查询草案，继续执行");
        }
        return result;
    }

    /** 单源生成（LLM → 归一 → schema 预检回流 ≤1 轮） */
    private GeneratedSource generateOne(CrossSourceJoinPlan plan, boolean leftSide, int sourceIndex, String dsId,
            String question, String retrievalContext, String reviewFeedback, String threadId) {
        String prompt = buildPrompt(plan, leftSide, sourceIndex, dsId, question, retrievalContext, reviewFeedback);
        String output = callModel(prompt);
        NormalizedSqlDraft draft = sqlDraftNormalizer == null
                ? null : sqlDraftNormalizer.normalize(output, null);
        if (draft == null || !draft.isUsable()) {
            String state = draft != null && draft.getIssue() == com.agenthub.ai.rcagent.sqlguard.normalize.SqlOutputIssue.NOT_READONLY
                    ? CrossSourceConstants.EXEC_SQL_NOT_READONLY : CrossSourceConstants.EXEC_SQL_EMPTY;
            log.info("RCAgent 跨源单源生成无有效只读 SQL: sourceIndex={}, state={}", sourceIndex, state);
            return GeneratedSource.invalid(state);
        }
        String sql = draft.getSql();
        // schema 预检回流（≤1 轮）：仅回流修正，不拦截（最终风险分级由验证节点完成）
        int fixRound = 0;
        while (fixRound < SCHEMA_FIX_MAX_ROUNDS && guardAdapter != null && guardAdapter.available()) {
            String checkReport;
            try {
                checkReport = guardAdapter.schemaCheck(dsId, sql);
            } catch (Exception e) {
                log.warn("RCAgent 跨源 schema 预检异常（忽略，不阻塞）: {}",
                        CrossSourceConstants.truncate(String.valueOf(e.getMessage()), 200));
                break;
            }
            if (checkReport == null || checkReport.isBlank() || !needsSchemaFix(checkReport)) {
                break;
            }
            fixRound++;
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                    "源 " + sourceIndex + " schema 预检发现表/字段问题，第 " + fixRound + " 轮修正中...", "RUNNING");
            String fixPrompt = prompt + "\n\n上一轮 SQL 未通过 schema 预检（表/字段缺失等），请基于以下预检反馈"
                    + "重新输出**单条**只读 SELECT（保持只读约束，严禁 DML/DDL，严禁 SELECT *）：\n"
                    + "【Schema 预检反馈】\n" + CrossSourceConstants.truncate(checkReport, 1500);
            String corrected = callModel(fixPrompt);
            NormalizedSqlDraft correctedDraft = sqlDraftNormalizer == null
                    ? null : sqlDraftNormalizer.normalize(corrected, null);
            if (correctedDraft == null || !correctedDraft.isUsable()) {
                break;
            }
            sql = correctedDraft.getSql();
        }
        return GeneratedSource.valid(sql);
    }

    /** 逐源提示词（技能指令 + 该源计划 + 检索上下文 + 审核意见） */
    private String buildPrompt(CrossSourceJoinPlan plan, boolean leftSide, int sourceIndex, String dsId,
            String question, String retrievalContext, String reviewFeedback) {
        String table = leftSide ? plan.getLeftTable() : plan.getRightTable();
        List<String> filters = leftSide ? plan.getLeftFilters() : plan.getRightFilters();
        List<String> projections = leftSide ? plan.getLeftProjections() : plan.getRightProjections();
        String aggregation = leftSide ? plan.getLeftAggregation() : plan.getRightAggregation();
        String ownKey = leftSide ? plan.getLeftKey() : plan.getRightKey();
        String otherKey = leftSide ? plan.getRightKey() : plan.getLeftKey();
        StringBuilder sb = new StringBuilder();
        sb.append(instruction == null ? "" : instruction).append('\n');
        sb.append("【当前任务数据】\n");
        sb.append("用户问题：").append(RcaNodeUtils.truncate(question, PROMPT_QUESTION_MAX_LEN)).append('\n');
        sb.append("源序号：").append(sourceIndex).append("（数据源 ID：").append(dsId).append("）\n");
        sb.append("目标表：").append(table == null ? "（未指定，依据检索上下文选择）" : table).append('\n');
        sb.append("期望投影：").append(projections == null || projections.isEmpty()
                ? "（未指定）" : String.join("；", truncateList(projections))).append('\n');
        sb.append("过滤条件（join 前下推）：").append(filters == null || filters.isEmpty()
                ? "（无）" : String.join("；", truncateList(filters))).append('\n');
        sb.append("聚合下推：").append(aggregation == null || aggregation.isBlank() ? "（无）" : aggregation).append('\n');
        sb.append("本源关联键：").append(ownKey == null ? "（未指定）" : ownKey)
                .append("（必须在 SELECT 结果中输出该列，且保持列名可被外层引用）\n");
        sb.append("另一源关联键：").append(otherKey == null ? "（未指定）" : otherKey)
                .append("（仅用于理解关联语义，禁止在本源 SQL 中引用另一源的表）\n");
        sb.append("跨源关联计划摘要：")
                .append(RcaNodeUtils.truncate(String.valueOf(plan.getJoinType()), PROMPT_PLAN_MAX_LEN))
                .append(" JOIN；关联键 ").append(ownKey).append(" = ").append(otherKey).append('\n');
        sb.append("检索上下文（表/字段/口径）：")
                .append(retrievalContext == null || retrievalContext.isBlank()
                        ? "（无）" : RcaNodeUtils.truncate(retrievalContext, PROMPT_RETRIEVAL_MAX_LEN))
                .append('\n');
        if (reviewFeedback != null && !reviewFeedback.isBlank()) {
            sb.append("【人工审核意见】").append(RcaNodeUtils.truncate(reviewFeedback, 1000)).append('\n');
        }
        sb.append("""
                【输出要求】
                  - 仅输出一条只读 SELECT 语句（纯 SQL，无 Markdown 围栏、无解释、无结尾分号）。
                  - 严禁 DML/DDL、严禁 SELECT *、严禁多语句、严禁在本 SQL 中关联另一数据源的表。
                  - 所有投影列必须使用语义化 AS 别名；过滤条件尽量下推到 WHERE。
                """);
        return sb.toString();
    }

    private static List<String> truncateList(List<String> items) {
        List<String> result = new ArrayList<>();
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                result.add(RcaNodeUtils.truncate(item, 200));
            }
        }
        return result;
    }

    /** 构造 FAILED 结果（含脱敏诊断「源 N」，不写任何 SQL） */
    private Map<String, Object> failure(String threadId, int sourceIndex, String message, String note) {
        if (threadId != null) {
            RcaWorkflowService.errorStatusDetails.put(threadId,
                    "FAILED — " + CrossSourceConstants.truncate(note, 300));
        }
        publish(threadId, RcaWorkflowKeys.WORKFLOW_STATUS, "FAILED", "FAILED");
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, message, "FAILED");
        publish(threadId, RcaWorkflowKeys.CROSS_SOURCE_NOTE, note, "FAILED");
        Map<String, Object> result = new HashMap<>();
        result.put(RcaWorkflowKeys.QUERY_RESULT, message);
        result.put(RcaWorkflowKeys.WORKFLOW_STATUS, "FAILED");
        result.put(RcaWorkflowKeys.CROSS_SOURCE_NOTE, note);
        return result;
    }

    private CrossSourceJoinPlan parsePlan(String json) {
        try {
            return JSON.parseObject(json, CrossSourceJoinPlan.class);
        } catch (Exception e) {
            log.warn("RCAgent 跨源生成：join_plan 解析失败（降级为失败）");
            return null;
        }
    }

    private CrossSourceProperties effectiveProperties() {
        return properties == null ? new CrossSourceProperties() : properties;
    }

    private static boolean needsSchemaFix(String report) {
        return report.contains("passed=false") || report.contains("exists=false");
    }

    private String callModel(String prompt) {
        if (chatModel == null) {
            return null;
        }
        try {
            StringBuilder sb = new StringBuilder();
            if (chatModel instanceof StreamingChatModel) {
                StreamingChatModel streaming = (StreamingChatModel) chatModel;
                streaming.stream(new Prompt(new UserMessage(prompt)))
                        .doOnNext(chunk -> {
                            String text = chunk.getResult() != null && chunk.getResult().getOutput() != null
                                    ? chunk.getResult().getOutput().getText() : "";
                            if (text != null && !text.isEmpty()) {
                                // 仅本地累积，SQL 草案增量/全量一律不推 SSE（审核敏感内容）
                                sb.append(text);
                            }
                        })
                        .blockLast();
            } else {
                String result = chatModel.call(new Prompt(new UserMessage(prompt)))
                        .getResult().getOutput().getText();
                if (result != null) {
                    sb.append(result);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("RCAgent 跨源逐源生成 LLM 调用失败: {}",
                    CrossSourceConstants.truncate(String.valueOf(e.getMessage()), 200));
            return null;
        }
    }

    private static String threadId(OverAllState state) {
        if (state == null) {
            return null;
        }
        return state.value(RcaWorkflowKeys.THREAD_ID_KEY).map(Object::toString).orElse(null);
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) {
            return;
        }
        eventBus.publish(threadId, key, value, status);
    }

    /** 单源生成结果载体（内部） */
    private record GeneratedSource(String sql, String state, boolean valid) {

        static GeneratedSource valid(String sql) {
            return new GeneratedSource(sql, CrossSourceConstants.EXEC_SUCCESS, true);
        }

        static GeneratedSource invalid(String state) {
            return new GeneratedSource(null, state, false);
        }
    }
}
