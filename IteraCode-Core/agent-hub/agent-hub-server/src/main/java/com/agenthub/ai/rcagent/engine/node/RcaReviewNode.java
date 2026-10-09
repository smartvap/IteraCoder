package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;
import com.agenthub.ai.rcagent.crosssource.model.SourceQuerySql;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RCAgent 人工审核节点（平行 ManualReviewNode 骨架，内容为 SQL+归因结论）。
 * <p>
 * 组装 review_content（SQL 草案 + 风险等级 + 归因结论摘要），经 eventBus 推送，
 * state 写 WAITING_REVIEW，交由 CompiledGraph interruptAfter(rcagent_review) 中断等待人工决策。
 * 摘要暂用原始内容截断组装，流式摘要后续接入 LLM 增强（P1）。</p>
 *
 * <p><b>跨源版式（module-003）</b>：{@code source_query_plans} 非空时使用跨源版式
 * （问题 / 整体风险等级 / 关联方式与关联键 / 各源 SQL 分节 / 关联说明 / 逐源验证分级）；
 * 否则 {@code else} 分支**逐字符保留**既有单源版式（单源会话永不写该键 ⇒ 零回归）。</p>
 */
@Slf4j
public class RcaReviewNode implements NodeAction {

    /** 跨源审核：问题截断长度 */
    private static final int CROSS_QUESTION_MAX_LEN = 1000;
    /** 跨源审核：单源 SQL 截断长度 */
    private static final int CROSS_SQL_MAX_LEN = 8000;
    /** 跨源审核：关联说明截断长度 */
    private static final int CROSS_NOTE_MAX_LEN = 1000;

    private final WorkflowEventBus eventBus;

    public RcaReviewNode(WorkflowEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sql = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SQL_GENERATE, "");
        String riskLevel = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RISK_LEVEL, "MEDIUM");
        String validateReport = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SQL_VALIDATE_REPORT, "");
        String attributionResult = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.ATTRIBUTION_RESULT, "");
        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String joinPlan = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.JOIN_PLAN, "");
        String sourcePlans = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SOURCE_QUERY_PLANS, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);

        String reviewContent = sourcePlans.isBlank()
                ? buildSingleSourceReview(question, riskLevel, sql, validateReport, attributionResult)
                : buildCrossSourceReview(question, riskLevel, joinPlan, sourcePlans, validateReport,
                        attributionResult);
        log.info("RCAgent 审核节点：已准备待审核内容（{} 字符），等待 StateGraph 暂停, threadId={}, crossSource={}",
                reviewContent.length(), threadId, !sourcePlans.isBlank());
        publish(threadId, RcaWorkflowKeys.REVIEW_CONTENT, reviewContent, "WAITING_REVIEW");
        publish(threadId, RcaWorkflowKeys.WORKFLOW_STATUS, "WAITING_REVIEW", "WAITING_REVIEW");
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                "会话已暂停，等待人工审核 SQL 与归因结论", "WAITING_REVIEW");

        Map<String, Object> result = new HashMap<>();
        result.put(RcaWorkflowKeys.REVIEW_CONTENT, reviewContent);
        result.put(RcaWorkflowKeys.WORKFLOW_STATUS, "WAITING_REVIEW");
        result.put(RcaWorkflowKeys.WORKFLOW_MESSAGE, "会话已暂停，等待人工审核 SQL 与归因结论");
        return result;
    }

    /** 既有单源版式（逐字符保持不变） */
    private String buildSingleSourceReview(String question, String riskLevel, String sql,
            String validateReport, String attributionResult) {
        StringBuilder review = new StringBuilder("===== 待审核 SQL 与结论摘要 =====\n");
        review.append("问题：").append(RcaNodeUtils.truncate(question, 1000)).append("\n");
        review.append("风险等级：").append(riskLevel).append("\n\n");
        if (sql != null && !sql.isBlank()) {
            review.append("===== SQL 草案 =====\n").append(RcaNodeUtils.truncate(sql, 8000)).append("\n\n");
        } else {
            review.append("===== SQL 草案 =====\n（无 SQL 草案）\n\n");
        }
        if (validateReport != null && !validateReport.isBlank()) {
            review.append("===== 验证分级 =====\n").append(RcaNodeUtils.truncate(validateReport, 3000)).append("\n\n");
        }
        if (attributionResult != null && !attributionResult.isBlank()) {
            review.append("===== 归因结论 =====\n").append(RcaNodeUtils.truncate(attributionResult, 4000)).append("\n\n");
        }
        review.append("请人工审核以上 SQL 与结论，决策：APPROVED(通过执行) / SENT_BACK(打回重写) / TERMINATED(终止会话)");
        return review.toString();
    }

    /** 跨源版式（api-contract §4.12.1 逐字符）：各源 SQL 分节 + 关联方式与键 + 逐源验证分级 */
    private String buildCrossSourceReview(String question, String riskLevel, String joinPlanJson,
            String sourcePlansJson, String validateReport, String attributionResult) {
        CrossSourceJoinPlan plan = parsePlan(joinPlanJson);
        List<SourceQuerySql> sqls = parseSqls(sourcePlansJson);
        int sourceCount = plan != null && plan.getSourceCount() != null
                ? plan.getSourceCount() : sqls.size();
        StringBuilder review = new StringBuilder("===== 待审核跨源关联（覆盖 ")
                .append(sourceCount).append(" 个数据源）=====\n");
        review.append("问题：").append(RcaNodeUtils.truncate(question, CROSS_QUESTION_MAX_LEN)).append("\n");
        review.append("整体风险等级：").append(riskLevel).append("\n");
        review.append("关联方式：").append(plan == null ? "（未知）" : String.valueOf(plan.getJoinType()))
                .append("；关联键：源 1.").append(plan == null ? "" : safe(plan.getLeftKey()))
                .append(" = 源 2.").append(plan == null ? "" : safe(plan.getRightKey())).append("\n\n");
        for (SourceQuerySql sql : sqls) {
            review.append("===== 源 ").append(sql.getSourceIndex()).append(" SQL 草案 =====\n")
                    .append(RcaNodeUtils.truncate(safe(sql.getSqlText()), CROSS_SQL_MAX_LEN)).append("\n\n");
        }
        review.append("===== 关联说明 =====\n")
                .append(RcaNodeUtils.truncate(buildPlanExplanation(plan), CROSS_NOTE_MAX_LEN)).append("\n\n");
        if (validateReport != null && !validateReport.isBlank()) {
            review.append("===== 验证分级（逐源）=====\n")
                    .append(RcaNodeUtils.truncate(validateReport, 3000)).append("\n\n");
        }
        if (attributionResult != null && !attributionResult.isBlank()) {
            review.append("===== 归因结论 =====\n")
                    .append(RcaNodeUtils.truncate(attributionResult, 4000)).append("\n\n");
        }
        review.append("请人工审核以上各源 SQL 与关联方式，决策：APPROVED(通过执行) / SENT_BACK(打回重写) / TERMINATED(终止会话)");
        return review.toString();
    }

    /** 关联说明（下推/聚合/过滤口径，仅供审核通道；不含 dsId/连接信息） */
    private static String buildPlanExplanation(CrossSourceJoinPlan plan) {
        if (plan == null) {
            return "（关联计划不可用）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("关联类型：").append(plan.getJoinType()).append(" JOIN；")
                .append("逐源查询在应用层按关联键合并（目标侧按探测侧键集合做半连接过滤下推，超阈值则分片，超分片上限回退内存关联）。");
        appendIfPresent(sb, "源 1 目标表：", plan.getLeftTable());
        appendIfPresent(sb, "源 2 目标表：", plan.getRightTable());
        if (plan.getLeftFilters() != null && !plan.getLeftFilters().isEmpty()) {
            sb.append("\n源 1 过滤下推：").append(String.join("；", plan.getLeftFilters()));
        }
        if (plan.getRightFilters() != null && !plan.getRightFilters().isEmpty()) {
            sb.append("\n源 2 过滤下推：").append(String.join("；", plan.getRightFilters()));
        }
        if (plan.getLeftAggregation() != null && !plan.getLeftAggregation().isBlank()) {
            sb.append("\n源 1 聚合下推：").append(plan.getLeftAggregation());
        }
        if (plan.getRightAggregation() != null && !plan.getRightAggregation().isBlank()) {
            sb.append("\n源 2 聚合下推：").append(plan.getRightAggregation());
        }
        if (plan.getPostJoinAggregation() != null && !plan.getPostJoinAggregation().isBlank()) {
            sb.append("\n合并后聚合：").append(plan.getPostJoinAggregation());
        }
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append('\n').append(label).append(value);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static CrossSourceJoinPlan parsePlan(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json, CrossSourceJoinPlan.class);
        } catch (Exception e) {
            log.warn("RCAgent 审核节点：join_plan 解析失败（跨源审核版式降级为无计划说明）");
            return null;
        }
    }

    private static List<SourceQuerySql> parseSqls(String json) {
        List<SourceQuerySql> sqls = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return sqls;
        }
        try {
            List<SourceQuerySql> parsed = JSON.parseArray(json, SourceQuerySql.class);
            if (parsed != null) {
                for (SourceQuerySql sql : parsed) {
                    if (sql != null) {
                        sqls.add(sql);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("RCAgent 审核节点：逐源 SQL JSON 解析失败（跨源审核版式降级）");
        }
        return sqls;
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
