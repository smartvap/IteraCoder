package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.service.RcaWorkflowService;
import com.agenthub.ai.rcagent.sqlguard.execute.SqlExecutionGuardService;
import com.agenthub.ai.rcagent.sqlguard.model.GuardedQueryResult;
import com.agenthub.ai.rcagent.sqlguard.model.RiskAssessment;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardQuery;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RCAgent 只读执行节点：调用 module-002/006 SqlExecutionGuardService.executeGuarded。
 * <p>
 * LOW 自动执行；MEDIUM/HIGH 必须在人工审核放行（resume APPROVED 写 review_decision=APPROVED）
 * 后传 requireManualApproval=true 才执行；BLOCK 已在验证节点拦截不会进入本节点。
 * 执行结果（QueryResult 行列摘要）序列化为文本写入 query_result；SQL 原文不落 query_result。
 * 执行失败（空 SQL/服务缺失/数据源不可达或 SQL 被拒绝）时写 workflow_status=FAILED 并复用
 * {@link RcaWorkflowService#errorStatusDetails}，保证 pushFinalState 判定 FAILED 而非 COMPLETED
 * （修复审查问题 2：可经 /recover 或切换数据源重试）。</p>
 */
@Slf4j
public class RcaExecuteNode implements NodeAction {

    private final WorkflowEventBus eventBus;
    private final SqlExecutionGuardService executionGuardService;

    public RcaExecuteNode(WorkflowEventBus eventBus, SqlExecutionGuardService executionGuardService) {
        this.eventBus = eventBus;
        this.executionGuardService = executionGuardService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sql = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SQL_GENERATE, "");
        String dsId = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.DS_ID, "");
        String riskLevel = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RISK_LEVEL, "LOW");
        String reviewDecision = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.REVIEW_DECISION, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);

        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在执行只读查询（风险等级 " + riskLevel + "）...", "RUNNING");

        if (sql == null || sql.isBlank()) {
            String msg = "执行失败：SQL 草案为空";
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, msg, "RUNNING");
            return failureResult(threadId, msg);
        }
        if (executionGuardService == null) {
            log.warn("RCAgent 执行节点：SqlExecutionGuardService 未注入（占位契约缺失），threadId={}", threadId);
            String msg = "执行降级：只读执行服务不可用（module-002/006 未接入），请检查依赖模块配置后重试";
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, msg, "RUNNING");
            return failureResult(threadId, msg);
        }
        try {
            boolean requireApproval = "MEDIUM".equals(riskLevel) || "HIGH".equals(riskLevel);
            SqlGuardQuery query = SqlGuardQuery.builder()
                    .dsId(dsId)
                    .sql(sql)
                    .maxRows(200)
                    .maxSeconds(10)
                    .requireManualApproval(requireApproval)
                    .threadId(threadId)
                    .build();
            GuardedQueryResult guarded = executionGuardService.executeGuarded(query);
            String resultText = formatGuardedResult(guarded, riskLevel, reviewDecision);
            publish(threadId, RcaWorkflowKeys.QUERY_RESULT, resultText, "RUNNING");
            return Map.of(RcaWorkflowKeys.QUERY_RESULT, resultText);
        } catch (Exception e) {
            log.warn("RCAgent 只读执行失败: {}", RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            String msg = "执行失败（数据源不可达或 SQL 被拒绝）："
                    + RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 500)
                    + "。请切换数据源重试或检查 SQL。";
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, msg, "RUNNING");
            return failureResult(threadId, msg);
        }
    }

    /**
     * 构造执行失败结果：写 workflow_status=FAILED（checkpoint 判定终态依据）+ errorStatusDetails 兜底原因，
     * 使 pushFinalState 落入 FAILED 而非 COMPLETED（修复审查问题 2）。
     */
    private Map<String, Object> failureResult(String threadId, String msg) {
        if (threadId != null) {
            RcaWorkflowService.errorStatusDetails.put(threadId, "FAILED — " + RcaNodeUtils.truncate(msg, 300));
        }
        publish(threadId, RcaWorkflowKeys.WORKFLOW_STATUS, "FAILED", "FAILED");
        Map<String, Object> result = new HashMap<>();
        result.put(RcaWorkflowKeys.QUERY_RESULT, msg);
        result.put(RcaWorkflowKeys.WORKFLOW_STATUS, "FAILED");
        return result;
    }

    private String formatGuardedResult(GuardedQueryResult guarded, String riskLevel, String reviewDecision) {
        if (guarded == null) {
            return "执行无返回（guarded result is null）";
        }
        StringBuilder sb = new StringBuilder("===== 查询执行结果 =====\n");
        RiskAssessment risk = guarded.getRisk();
        if (risk != null) {
            sb.append("风险等级：").append(risk.getRiskLevel() == null ? riskLevel : risk.getRiskLevel()).append("\n");
            if (risk.getBlockedReason() != null && !risk.getBlockedReason().isBlank()) {
                sb.append("拦截原因：").append(risk.getBlockedReason()).append("\n");
            }
        }
        Boolean executed = guarded.getExecuted();
        if (Boolean.TRUE.equals(executed) && guarded.getResult() != null) {
            var result = guarded.getResult();
            sb.append("行数：").append(result.getRowCount() == null ? 0 : result.getRowCount())
                    .append(Boolean.TRUE.equals(result.getTruncated()) ? "（已截断）" : "").append("\n");
            List<String> columns = result.getColumnNames();
            if (columns != null && !columns.isEmpty()) {
                sb.append("列：").append(String.join(", ", columns)).append("\n");
            }
            if (result.getRows() != null && !result.getRows().isEmpty()) {
                int shown = 0;
                for (Map<String, Object> row : result.getRows()) {
                    if (shown >= 30) break;
                    sb.append("| ");
                    if (columns != null) {
                        for (String col : columns) {
                            Object v = row.get(col);
                            sb.append(col).append("=").append(displayValue(v)).append(" ; ");
                        }
                    } else {
                        sb.append(row);
                    }
                    sb.append("|\n");
                    shown++;
                }
                if (result.getRows().size() > shown) {
                    sb.append("... 共 ").append(result.getRows().size()).append(" 行\n");
                }
            }
            if (Boolean.TRUE.equals(result.getSensitiveMasked())) {
                sb.append("（注意：本次查询命中了敏感字段脱敏）\n");
            }
        } else {
            sb.append("未实际执行（")
                    .append(reviewDecision.isBlank() ? "LOW 自动未放行或策略要求审核" : "审核决策=" + reviewDecision)
                    .append("）\n");
            if (guarded.getReport() != null && guarded.getReport().getLevels() != null) {
                for (var l : guarded.getReport().getLevels()) {
                    if (l != null && l.getResult() != null && (l.getResult().contains("SKIP") || l.getResult().contains("FAIL"))) {
                        sb.append("L").append(l.getLevel()).append(": ").append(l.getResult())
                                .append(" - ").append(l.getReason() == null ? "" : l.getReason()).append("\n");
                    }
                }
            }
        }
        return RcaNodeUtils.truncate(sb.toString(), 8000);
    }

    private static String displayValue(Object v) {
        if (v == null) return "NULL";
        String s = v.toString();
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
