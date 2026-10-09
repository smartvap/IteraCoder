package com.agenthub.ai.rcagent.attribution.adapter;

import com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode;
import com.agenthub.ai.rcagent.attribution.model.QueryOutcome;
import com.agenthub.ai.rcagent.sqlguard.execute.SqlExecutionGuardService;
import com.agenthub.ai.rcagent.sqlguard.exception.SqlGuardException;
import com.agenthub.ai.rcagent.sqlguard.model.GuardedQueryResult;
import com.agenthub.ai.rcagent.sqlguard.model.LevelResult;
import com.agenthub.ai.rcagent.sqlguard.model.SqlGuardQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * module-006 sql-validate-guard 适配（GuardAdapter，进程内 SPI，BR-002 唯一执行通道）。
 *
 * <p>归因 SQL 只读执行统一走 {@link SqlExecutionGuardService#executeGuarded}（五级验证 + 风险分级 +
 * 只读执行），BLOCK 零放行、MED/HIGH 需 requireManualApproval=true 人工放行后执行；
 * 安全拦截/失败一律结构化返回（blocked=true），不抛异常打断编排（api-contract 第 70 行约定）。</p>
 */
@Slf4j
@Component
public class GuardAdapter {

    private final SqlExecutionGuardService sqlExecutionGuardService;

    public GuardAdapter(SqlExecutionGuardService sqlExecutionGuardService) {
        this.sqlExecutionGuardService = sqlExecutionGuardService;
    }

    /**
     * 将一条归因 SQL 提交 module-006 做验证 + 分级 + 只读执行，并把风险/执行结果回填到 outcome。
     *
     * @param seed                  半成品 outcome（query_id/query_type/node_id/ds_id/sql_text/sql_abstract 已就绪）
     * @param requireManualApproval 是否已人工放行（MED/HIGH 经审核 approve 后置 true 放行执行）
     */
    public QueryOutcome guard(QueryOutcome seed, boolean requireManualApproval) {
        SqlGuardQuery query = SqlGuardQuery.builder()
                .dsId(seed.getDsId())
                .sql(seed.getSqlText())
                .requireManualApproval(requireManualApproval)
                .skipResultCheck(false)
                .operatorId(null)
                .build();
        try {
            GuardedQueryResult guarded = sqlExecutionGuardService.executeGuarded(query);
            seed.setRequireManualApproval(requireManualApproval);
            if (guarded == null) {
                seed.setBlocked(true);
                seed.setErrCode(String.valueOf(AttributionErrorCode.DEPENDENCY_UNAVAILABLE));
                seed.setErrMsg("归因 SQL 受保护执行返回空结果，请检查数据源/安全体系");
                return seed;
            }
            fillFromGuarded(seed, guarded);
        } catch (SqlGuardException e) {
            // module-006 基础设施异常（数据源不可用/参数非法等）结构化收敛返回，message 不含连接细节/SQL 原文
            log.warn("attribution: 归因 SQL 受保护执行失败 code={}", e.getCode());
            seed.setBlocked(true);
            seed.setExecuted(false);
            seed.setErrCode(String.valueOf(e.getCode()));
            seed.setErrMsg(e.getCode() == 43007
                    ? "归因 SQL 受保护执行依赖服务不可用（请检查数据源与安全体系）"
                    : "归因 SQL 被安全体系拒绝，请检查口径/表映射");
        } catch (RuntimeException e) {
            log.warn("attribution: 归因 SQL 受保护执行异常(收敛提示): {}", e.getMessage());
            seed.setBlocked(true);
            seed.setExecuted(false);
            seed.setErrCode(String.valueOf(AttributionErrorCode.DEPENDENCY_UNAVAILABLE));
            seed.setErrMsg("归因 SQL 受保护执行异常，请稍后重试");
        }
        return seed;
    }

    private void fillFromGuarded(QueryOutcome seed, GuardedQueryResult guarded) {
        boolean executed = Boolean.TRUE.equals(guarded.getExecuted());
        seed.setExecuted(executed);
        if (guarded.getResult() != null) {
            seed.setResult(guarded.getResult());
            seed.setCostMs(guarded.getResult().getCostMs() == null ? null : guarded.getResult().getCostMs().intValue());
        }
        if (guarded.getRisk() != null) {
            seed.setRiskLevel(guarded.getRisk().getRiskLevel());
            seed.setVerdict(guarded.getRisk().getVerdict());
            seed.setBlockReason(guarded.getRisk().getBlockedReason());
        }
        if (guarded.getReport() != null) {
            if (guarded.getReport().getFixSuggestions() != null && !guarded.getReport().getFixSuggestions().isEmpty()) {
                seed.setFixSuggestion(com.agenthub.ai.rcagent.sqlguard.util.SqlTextUtil.truncate(
                        guarded.getReport().getFixSuggestions().get(0).getMessage(), 500));
            }
            seed.setResultCheckJson(levelSummaryJson(guarded.getReport().getLevels()));
        }
        if (!executed) {
            boolean reviewPending = "REVIEW".equalsIgnoreCase(seed.getVerdict());
            if (reviewPending && !seed.isRequireManualApproval()) {
                // MED/HIGH 归因查询：进入归因侧 QUERY_REVIEWING 待审（非禁止执行），
                // 审核 APPROVE 后以 requireManualApproval=true 复跑放行
                seed.setBlocked(false);
                seed.setReviewStatus(com.agenthub.ai.rcagent.attribution.constant.QueryReviewStatus.NONE.name());
                seed.setErrMsg(null);
            } else {
                // BLOCK/敏感拒绝/未放行：禁止执行，结构化安全拦截
                seed.setBlocked(true);
                seed.setErrMsg(seed.getBlockReason() == null || seed.getBlockReason().isBlank()
                        ? "归因 SQL 未获放行执行（BLOCK 或需人工审核）" : seed.getBlockReason());
            }
        } else {
            seed.setBlocked(false);
            seed.setReviewStatus(com.agenthub.ai.rcagent.attribution.constant.QueryReviewStatus.NONE.name());
        }
    }

    private String levelSummaryJson(java.util.List<LevelResult> levels) {
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        java.util.List<com.alibaba.fastjson2.JSONObject> arr = new java.util.ArrayList<>();
        for (LevelResult l : levels) {
            if (l == null || l.getLevel() == null || l.getResult() == null) {
                continue;
            }
            com.alibaba.fastjson2.JSONObject o = new com.alibaba.fastjson2.JSONObject();
            o.put("level", l.getLevel());
            o.put("result", l.getResult());
            if (l.getReason() != null && !l.getReason().isBlank()) {
                o.put("reason", com.agenthub.ai.rcagent.sqlguard.util.SqlTextUtil.truncate(l.getReason(), 200));
            }
            arr.add(o);
        }
        return arr.isEmpty() ? null : com.alibaba.fastjson2.JSON.toJSONString(arr);
    }
}
