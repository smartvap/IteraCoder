package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.support.RcaPlanViewParser;
import com.agenthub.ai.rcagent.sqlguard.model.LevelResult;
import com.agenthub.ai.rcagent.sqlguard.model.RiskAssessment;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardRequest;
import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import com.agenthub.ai.rcagent.sqlguard.model.ValidationReport;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardService;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RCAgent 验证分级节点：调用 module-006 sql-validate-guard 对 SQL 做五级验证 + 风险分级。
 * <p>
 * 输出 risk_level（BLOCK/LOW/MEDIUM/HIGH）与 sql_validate_report 文本，供条件边路由：
 * BLOCK 拦截（不进执行/审核）、LOW 自动、MEDIUM/HIGH 进入人工审核。
 * SQL 为空属内容问题 → MEDIUM 转人工打回；但 guard 依赖缺失/调用失败（数据源不可达等）
 * 一律 fail-closed 抛异常终止（50010 语义），由外层 handleWorkflowException 置 FAILED，
 * 绝不降级为 MEDIUM「人工放行即可执行」绕过分级体系（修复审查问题 6，对齐 NFR-BE-005/BR-002）。</p>
 */
@Slf4j
public class RcaValidateNode implements NodeAction {

    private final WorkflowEventBus eventBus;
    private final SqlGuardService sqlGuardService;

    public RcaValidateNode(WorkflowEventBus eventBus, SqlGuardService sqlGuardService) {
        this.eventBus = eventBus;
        this.sqlGuardService = sqlGuardService;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String sql = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SQL_GENERATE, "");
        String dsId = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.DS_ID, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在执行 SQL 验证与风险分级...", "RUNNING");

        if (sql == null || sql.isBlank()) {
            log.warn("RCAgent 验证节点：SQL 为空, threadId={}", threadId);
            String report = "验证失败：SQL 草案为空";
            publish(threadId, RcaWorkflowKeys.SQL_VALIDATE_REPORT, report, "RUNNING");
            return Map.of(
                    RcaWorkflowKeys.SQL_VALIDATE_REPORT, report,
                    RcaWorkflowKeys.RISK_LEVEL, "MEDIUM",
                    RcaWorkflowKeys.WORKFLOW_MESSAGE, "SQL 为空，等待人工处理"
            );
        }
        if (sqlGuardService == null) {
            // NFR-BE-005/BR-002 fail-closed：验证服务缺失不得降级为「MEDIUM 转人工放行」，
            // 否则未分级 SQL（含 BLOCK 型）在服务不可达时可被人工直接放行，绕过安全体系（修复审查问题 6）
            log.warn("RCAgent 验证节点：SqlGuardService 未注入（占位契约缺失），按失败终止（fail-closed）, threadId={}", threadId);
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR,
                    "SQL 验证服务不可用（sql-validate-guard 未接入或数据源不可达），本次会话已按失败终止，"
                            + "请检查依赖模块配置后重新发起或切换数据源重试（50010）");
        }
        try {
            // 把 sql_plan 解析为结构化视图传入 L3 语义对照，避免"规划有筛选条件、生成 SQL 无 WHERE"
            // 这类明显不一致被 L3 SKIP 静默放过（解析失败返回 null → 保持 SKIP 旧行为）
            String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
            String planText = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SQL_PLAN, "");
            SqlPlanView plan = RcaPlanViewParser.parse(planText, question, dsId);
            SqlGuardRequest.SqlGuardRequestBuilder requestBuilder = SqlGuardRequest.builder()
                    .dsId(dsId)
                    .sql(sql)
                    .maxRows(500)
                    .maxSeconds(10)
                    .threadId(threadId);
            if (plan != null) {
                requestBuilder.plan(plan);
            }
            SqlGuardRequest request = requestBuilder.build();
            ValidationReport report = sqlGuardService.validate(request);
            String riskLevel = resolveRisk(report);
            String reportText = formatReport(report, riskLevel);
            publish(threadId, RcaWorkflowKeys.SQL_VALIDATE_REPORT, reportText, "RUNNING");
            publish(threadId, RcaWorkflowKeys.RISK_LEVEL, riskLevel, "RUNNING");
            if ("BLOCK".equals(riskLevel)) {
                publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                        "SQL 被安全体系拦截（BLOCK），本次会话终止执行", "RUNNING");
            } else {
                publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                        "验证完成，风险等级=" + riskLevel, "RUNNING");
            }
            return Map.of(
                    RcaWorkflowKeys.SQL_VALIDATE_REPORT, reportText,
                    RcaWorkflowKeys.RISK_LEVEL, riskLevel
            );
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            // 数据源不可达/依赖模块异常：结构化失败（50010 语义）而非 MEDIUM 转人工可放行（修复审查问题 6）。
            // 由 StateGraph 流 onError → handleWorkflowException 置 FAILED，允许切换数据源后 recover/重试。
            log.warn("RCAgent 验证分级调用失败: {}", RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR,
                    "数据源不可达或验证服务异常（50010），已按失败终止、避免未分级 SQL 进入执行通道，请切换数据源重试："
                            + RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
        }
    }

    /** 从验证报告中解析最终风险等级（BLOCK > MEDIUM/HIGH > LOW > 兜底 MEDIUM） */
    private String resolveRisk(ValidationReport report) {
        if (report == null || report.getRisk() == null) {
            return "MEDIUM";
        }
        RiskAssessment risk = report.getRisk();
        String level = risk.getRiskLevel();
        if (level != null && !level.isBlank()) {
            return normalizeRisk(level);
        }
        if (Boolean.FALSE.equals(report.getPassed())) {
            return "BLOCK";
        }
        return "MEDIUM";
    }

    private String normalizeRisk(String level) {
        String upper = level.toUpperCase();
        if (upper.contains("BLOCK")) return "BLOCK";
        if (upper.contains("HIGH")) return "HIGH";
        if (upper.contains("MEDIUM") || upper.contains("MED")) return "MEDIUM";
        if (upper.contains("LOW")) return "LOW";
        return "MEDIUM";
    }

    private String formatReport(ValidationReport report, String riskLevel) {
        if (report == null) {
            return "验证无返回报告，风险等级=" + riskLevel;
        }
        StringBuilder sb = new StringBuilder("===== SQL 验证报告（风险等级 " + riskLevel + "）=====\n");
        List<LevelResult> levels = report.getLevels();
        if (levels != null) {
            for (LevelResult l : levels) {
                if (l == null) continue;
                sb.append("L").append(l.getLevel()).append(": ")
                        .append(l.getResult() == null ? "" : l.getResult());
                if (l.getReason() != null && !l.getReason().isBlank()) {
                    sb.append(" - ").append(RcaNodeUtils.truncate(l.getReason(), 200));
                }
                sb.append("\n");
            }
        }
        RiskAssessment risk = report.getRisk();
        if (risk != null) {
            if (risk.getBlockedReason() != null && !risk.getBlockedReason().isBlank()) {
                sb.append("拦截原因: ").append(RcaNodeUtils.truncate(risk.getBlockedReason(), 300)).append("\n");
            }
            if (risk.getReviewHint() != null && !risk.getReviewHint().isBlank()) {
                sb.append("审核提示: ").append(RcaNodeUtils.truncate(risk.getReviewHint(), 300)).append("\n");
            }
        }
        return RcaNodeUtils.truncate(sb.toString(), 4000);
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
