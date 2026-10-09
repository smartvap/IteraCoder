package com.agenthub.ai.rcagent.sqlguard.validate;

import com.agenthub.ai.dbaccess.model.QueryResult;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardLevelResult;
import com.agenthub.ai.rcagent.sqlguard.model.FixSuggestion;
import com.agenthub.ai.rcagent.sqlguard.model.LevelResult;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import com.agenthub.ai.rcagent.sqlguard.model.TimeRange;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * L5 Result 结果合理性检查器（FR-003/BR-004）。
 *
 * <p>执行后检查：空结果/数量级异常/时间范围/聚合口径/JOIN 膨胀。
 * 异常输出 WARN/FAIL + fixSuggestions 供 workflow 回退（最多 repair_max_rounds 轮）。</p>
 */
@Component
public class L5ResultChecker {

    /**
     * 执行后结果检查。
     *
     * @return LevelResult（L5 PASS/WARN/FAIL/SKIP）
     */
    public LevelResult check(SqlFeature feature, SqlPlanView plan, QueryResult result) {
        if (feature == null) {
            return LevelResult.builder().level("L5").result("FAIL").reason("缺少 SQL 特征，结果无法复核").build();
        }
        if (result == null) {
            return LevelResult.builder().level("L5").result("SKIP").reason("无执行结果（未执行/explain）").build();
        }
        int rowCount = result.getRowCount() == null ? 0 : result.getRowCount();
        boolean truncated = Boolean.TRUE.equals(result.getTruncated());

        List<String> reasons = new ArrayList<>();
        // 1) 空结果：规划含指标但结果 0 行（可能是口径/过滤过严）
        if (rowCount == 0 && plan != null && !CollectionUtils.isEmpty(plan.getMetrics())) {
            reasons.add("空结果与规划指标预期不符，请核对时间范围/过滤口径");
        }
        // 2) 截断/数量级：结果被截断或行数远超预期
        if (truncated) {
            reasons.add("结果超过 maxRows 已截断，如需全量请缩小范围或分页");
        }
        if (rowCount > 10_000) {
            reasons.add("结果行数数量级较大(" + rowCount + ")，请确认过滤条件与聚合口径");
        }
        // 3) JOIN 膨胀：多表 JOIN 且结果远超阈值
        int joinCount = feature.getJoinCount() == null ? 0 : feature.getJoinCount();
        if (joinCount > 0 && rowCount > 5_000) {
            reasons.add("JOIN 结果膨胀(" + joinCount + " 表 JOIN 返回 " + rowCount + " 行)，请复核 JOIN 条件与业务口径");
        }
        // 4) 时间范围意图：plan 给出时间范围但 SQL 无 WHERE 时间过滤（尽力）
        if (plan != null && plan.getTimeRange() != null && hasTimeBound(plan.getTimeRange())
                && (feature.getWhereCount() == null || feature.getWhereCount() == 0)) {
            reasons.add("规划含时间范围意图但 SQL 无 WHERE 条件，疑似全表扫描口径");
        }

        if (reasons.isEmpty()) {
            return LevelResult.builder().level("L5").result("PASS")
                    .detail("结果行数=" + rowCount + (truncated ? "(截断)" : ""))
                    .reason("").build();
        }
        // 回退/复核语义（data-model passed 定义 / BR-004）：
        // 明显数量级异常或结果被截断 → FAIL（executeGuarded 据此置 report.passed=false 驱动 workflow 回退）；
        // 其余口径/时间范围/空结果提示 → WARN（不阻断）。
        boolean severe = rowCount > 10_000 || truncated;
        return LevelResult.builder().level("L5")
                .result(severe ? SqlGuardLevelResult.FAIL.name() : SqlGuardLevelResult.WARN.name())
                .detail(String.join("；", reasons))
                .reason(severe
                        ? "L5 Result 复核发现严重结果异常（数量级/截断），按 FAIL 驱动 workflow 回退"
                        : "L5 Result 复核发现潜在口径问题（由 workflow 消费修复建议）").build();
    }

    /**
     * 生成 L5 对应的修复建议。
     */
    public List<FixSuggestion> suggestions(SqlFeature feature, SqlPlanView plan, QueryResult result) {
        List<FixSuggestion> list = new ArrayList<>();
        if (result == null || feature == null) {
            return list;
        }
        if (Boolean.TRUE.equals(result.getTruncated())) {
            list.add(FixSuggestion.builder().action("ADD_LIMIT").target("result")
                    .message("结果已截断，请添加/收紧过滤条件或缩小时间范围").build());
        }
        if (feature.getJoinCount() != null && feature.getJoinCount() > 0) {
            list.add(FixSuggestion.builder().action("FIX_JOIN").target("join")
                    .message("复核 JOIN 关联条件，避免结果膨胀").build());
        }
        if (plan != null && plan.getTimeRange() != null
                && (feature.getWhereCount() == null || feature.getWhereCount() == 0)) {
            list.add(FixSuggestion.builder().action("ADD_FILTER").target("where")
                    .message("补充时间范围过滤，避免全表口径").build());
        }
        return list;
    }

    private boolean hasTimeBound(TimeRange range) {
        return (range.getStart() != null && !range.getStart().isBlank())
                || (range.getEnd() != null && !range.getEnd().isBlank());
    }
}
