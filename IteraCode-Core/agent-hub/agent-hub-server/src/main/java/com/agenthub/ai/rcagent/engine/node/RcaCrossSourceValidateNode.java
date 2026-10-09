package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.crosssource.CrossSourceConstants;
import com.agenthub.ai.rcagent.crosssource.CrossSourcePlanViewAdapter;
import com.agenthub.ai.rcagent.crosssource.CrossSourceSqlGuardAdapter;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;
import com.agenthub.ai.rcagent.crosssource.model.SourceQuerySql;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.sqlguard.model.LevelResult;
import com.agenthub.ai.rcagent.sqlguard.model.RiskAssessment;
import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import com.agenthub.ai.rcagent.sqlguard.model.ValidationReport;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * RCAgent 跨源逐源验证节点（module-003，新增）。
 *
 * <p>分支契约（api-contract §4.4.1 V0~V6）：逐源调用既有 {@code SqlGuardService.validate}
 * （经 {@link CrossSourceSqlGuardAdapter}，{@code plan} 由 {@link CrossSourcePlanViewAdapter} 构造）；
 * 逐源风险归一与既有 {@code RcaValidateNode.normalizeRisk} **逐字符一致**；
 * 整体风险取最严 {@code BLOCK &gt; HIGH &gt; MEDIUM &gt; LOW}（null/异常源按 MEDIUM）；
 * 输出 {@code sql_validate_report}（逐源版式，**无 SQL 原文、无 dsId**）+ {@code risk_level}。</p>
 *
 * <p><b>fail-closed</b>：校验服务缺失 / 调用异常 → 抛 {@link BusinessException}（50010 语义），
 * 绝不降级为「MEDIUM 转人工放行」（与既有 {@code RcaValidateNode} 一致）。</p>
 */
@Slf4j
public class RcaCrossSourceValidateNode implements NodeAction {

    /** 单源报告块截断长度 */
    private static final int SOURCE_BLOCK_MAX_LEN = 3000;
    /** 整体报告截断长度 */
    private static final int REPORT_MAX_LEN = 6000;
    /** 单条级别原因截断长度 */
    private static final int REASON_MAX_LEN = 200;

    private final WorkflowEventBus eventBus;
    private final CrossSourceSqlGuardAdapter guardAdapter;
    private final CrossSourcePlanViewAdapter planViewAdapter;

    public RcaCrossSourceValidateNode(WorkflowEventBus eventBus, CrossSourceSqlGuardAdapter guardAdapter,
            CrossSourcePlanViewAdapter planViewAdapter) {
        this.eventBus = eventBus;
        this.guardAdapter = guardAdapter;
        this.planViewAdapter = planViewAdapter;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String threadId = threadId(state);
        String plansJson = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SOURCE_QUERY_PLANS, "");
        // V1：无逐源 SQL（生成阶段失败等）→ 不写任何验证键
        if (plansJson.isBlank()) {
            log.info("RCAgent 跨源验证：无逐源 SQL，跳过验证");
            return Map.of();
        }
        // V5：fail-closed（服务缺失禁止降级放行）
        if (guardAdapter == null || !guardAdapter.available()) {
            log.warn("RCAgent 跨源验证：SqlGuardService 未注入（占位契约缺失），按失败终止（fail-closed）");
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR,
                    "SQL 验证服务不可用（sql-validate-guard 未接入或数据源不可达），本次跨源会话已按失败终止，"
                            + "请检查依赖模块配置后重新发起或切换数据源重试（50010）");
        }
        List<SourceQuerySql> sqls = parseSqls(plansJson);
        if (sqls.isEmpty()) {
            return Map.of();
        }
        CrossSourceJoinPlan plan = parsePlan(RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.JOIN_PLAN, ""));
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在执行逐源 SQL 验证与风险分级...", "RUNNING");

        List<String> perSourceBlocks = new ArrayList<>();
        String overallRisk = "LOW";
        try {
            for (SourceQuerySql sql : sqls) {
                int sourceIndex = sql.getSourceIndex() == null ? perSourceBlocks.size() + 1 : sql.getSourceIndex();
                int rowLimit = sql.getRowLimit() == null ? 2000 : sql.getRowLimit();
                SqlPlanView planView = planViewAdapter == null ? null : planViewAdapter.from(plan, sourceIndex);
                ValidationReport report = guardAdapter.validate(sql.getDsId(), sql.getSqlText(), planView,
                        rowLimit, threadId);
                String risk = normalizeRisk(report);
                overallRisk = strictest(overallRisk, risk);
                perSourceBlocks.add(formatSourceBlock(sourceIndex, risk, report));
            }
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            // V5：调用异常 fail-closed（50010 语义），由外层置 FAILED；禁止降级为 MEDIUM 放行
            log.warn("RCAgent 跨源逐源验证失败（fail-closed）: {}",
                    CrossSourceConstants.truncate(String.valueOf(e.getMessage()), 200));
            throw new BusinessException(ErrorCode.API_REQUEST_ERROR,
                    "数据源不可达或验证服务异常（50010），已按失败终止、避免未分级 SQL 进入执行通道，请切换数据源重试："
                            + CrossSourceConstants.truncate(String.valueOf(e.getMessage()), 200));
        }

        String reportText = CrossSourceConstants.truncate(
                "===== 跨源 SQL 验证报告（整体风险等级 " + overallRisk + "）=====\n"
                        + String.join("", perSourceBlocks)
                        + "（整体）风险等级：" + overallRisk,
                REPORT_MAX_LEN);
        log.info("RCAgent 跨源验证完成: sourceCount={}, overallRisk={}", sqls.size(), overallRisk);
        publish(threadId, RcaWorkflowKeys.SQL_VALIDATE_REPORT, reportText, "RUNNING");
        publish(threadId, RcaWorkflowKeys.RISK_LEVEL, overallRisk, "RUNNING");
        if ("BLOCK".equals(overallRisk)) {
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                    "跨源 SQL 被安全体系拦截（BLOCK），本次会话终止执行", "RUNNING");
        } else {
            publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                    "跨源验证完成，整体风险等级=" + overallRisk, "RUNNING");
        }
        return Map.of(
                RcaWorkflowKeys.SQL_VALIDATE_REPORT, reportText,
                RcaWorkflowKeys.RISK_LEVEL, overallRisk);
    }

    /** 单源报告块（§4.4.2 逐字符版式，无 SQL 原文 / 无 dsId） */
    private static String formatSourceBlock(int sourceIndex, String risk, ValidationReport report) {
        StringBuilder sb = new StringBuilder("【源 ").append(sourceIndex).append("】风险等级：")
                .append(risk).append('\n');
        if (report != null && report.getLevels() != null) {
            for (LevelResult level : report.getLevels()) {
                if (level == null) {
                    continue;
                }
                sb.append("L").append(level.getLevel()).append(": ")
                        .append(level.getResult() == null ? "" : level.getResult());
                if (level.getReason() != null && !level.getReason().isBlank()) {
                    sb.append(" - ").append(CrossSourceConstants.truncate(level.getReason(), REASON_MAX_LEN));
                }
                sb.append('\n');
            }
        }
        RiskAssessment riskAssessment = report == null ? null : report.getRisk();
        if (riskAssessment != null && riskAssessment.getBlockedReason() != null
                && !riskAssessment.getBlockedReason().isBlank()) {
            sb.append("拦截原因：")
                    .append(CrossSourceConstants.truncate(riskAssessment.getBlockedReason(), 300)).append('\n');
        }
        return CrossSourceConstants.truncate(sb.toString(), SOURCE_BLOCK_MAX_LEN);
    }

    /** 逐源风险归一（与既有 {@code RcaValidateNode.normalizeRisk} 逐字符一致） */
    static String normalizeRisk(ValidationReport report) {
        if (report == null || report.getRisk() == null) {
            return "MEDIUM";
        }
        String level = report.getRisk().getRiskLevel();
        if (level != null && !level.isBlank()) {
            return normalizeRiskText(level);
        }
        if (Boolean.FALSE.equals(report.getPassed())) {
            return "BLOCK";
        }
        return "MEDIUM";
    }

    private static String normalizeRiskText(String level) {
        String upper = level.toUpperCase(Locale.ROOT);
        if (upper.contains("BLOCK")) return "BLOCK";
        if (upper.contains("HIGH")) return "HIGH";
        if (upper.contains("MEDIUM") || upper.contains("MED")) return "MEDIUM";
        if (upper.contains("LOW")) return "LOW";
        return "MEDIUM";
    }

    /** 整体风险取最严（BLOCK=4 &gt; HIGH=3 &gt; MEDIUM=2 &gt; LOW=1） */
    static String strictest(String current, String candidate) {
        return rank(candidate) > rank(current) ? candidate : current;
    }

    private static int rank(String risk) {
        if (risk == null) {
            return 2;
        }
        return switch (risk.toUpperCase(Locale.ROOT)) {
            case "BLOCK" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 2;
        };
    }

    private List<SourceQuerySql> parseSqls(String json) {
        List<SourceQuerySql> sqls = new ArrayList<>();
        try {
            List<SourceQuerySql> parsed = JSON.parseArray(json, SourceQuerySql.class);
            if (parsed != null) {
                for (SourceQuerySql sql : parsed) {
                    if (sql != null && sql.getSqlText() != null && !sql.getSqlText().isBlank()) {
                        sqls.add(sql);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("RCAgent 跨源验证：逐源 SQL JSON 解析失败，按无 SQL 处理");
        }
        return sqls;
    }

    private CrossSourceJoinPlan parsePlan(String json) {
        if (json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json, CrossSourceJoinPlan.class);
        } catch (Exception e) {
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
}
