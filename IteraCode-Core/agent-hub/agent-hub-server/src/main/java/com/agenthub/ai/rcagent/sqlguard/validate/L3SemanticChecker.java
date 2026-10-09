package com.agenthub.ai.rcagent.sqlguard.validate;

import com.agenthub.ai.rcagent.sqlguard.model.LevelResult;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import com.agenthub.ai.rcagent.sqlguard.util.SqlTextUtil;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * L3 Semantic 尽力校验器（FR-003/BR-004）。
 *
 * <p>对照 SqlPlanView 判断 SQL 是否回答用户问题；<b>尽力校验</b>——只有明显不一致才 FAIL，
 * WARN 不阻断 Execution（靠 L5 Result + 用户反馈兜底）。plan 缺失 → SKIP。</p>
 */
@Component
public class L3SemanticChecker {

    /**
     * 筛选条件比较语义（=、&lt;&gt;、&gt;、&lt;、in、like、between、is 或中文"等于/大于/小于/属于"等）。
     * 用于 filters FAIL 前的保守兜底：仅"看起来是真实筛选"的项才判定丢失筛选意图，
     * 避免规划解析噪声（未识别干净的占位/描述文本）导致误 FAIL。
     */
    private static final Pattern FILTER_COMPARISON = Pattern.compile(
            "=|<>|!=|>=|<=|>|<|\\bin\\b|\\blike\\b|\\bbetween\\b|\\bis\\b"
                    + "|等于|大于|小于|不小于|不大于|不等于|属于|介于|包含|不为|超过|低于|高于",
            Pattern.CASE_INSENSITIVE);

    /**
     * 语义对照校验。
     *
     * @return LevelResult（L3 PASS/WARN/SKIP/FAIL）；FAIL 仅用于 plan 目标表与 SQL 明显不符
     */
    public LevelResult check(SqlFeature feature, SqlPlanView plan) {
        if (plan == null) {
            return LevelResult.builder()
                    .level("L3")
                    .result("SKIP")
                    .reason("未提供 SQL 规划 Plan，跳过语义对照（靠 L5 Result 与反馈兜底）")
                    .build();
        }
        if (feature == null || feature.getTables() == null || feature.getTables().isEmpty()) {
            return LevelResult.builder().level("L3").result("WARN")
                    .detail("SQL 无有效表引用，无法与规划对照")
                    .reason("候选 SQL 未解析出表，建议重写 SQL").build();
        }

        List<String> sqlTables = feature.tableNames();
        List<String> planTables = plan.getTargetTables() == null ? new ArrayList<>() : plan.getTargetTables();

        // 1) 规划目标表与 SQL 表完全无交集 → 明显不一致 FAIL（供回退重生成）
        if (!CollectionUtils.isEmpty(planTables)) {
            boolean anyMatch = false;
            for (String t : planTables) {
                if (t == null) {
                    continue;
                }
                for (String st : sqlTables) {
                    if (st != null && st.equalsIgnoreCase(t)) {
                        anyMatch = true;
                        break;
                    }
                }
            }
            if (!anyMatch) {
                return LevelResult.builder().level("L3").result("FAIL")
                        .detail("SQL 引用表与规划目标表不一致")
                        .reason("规划目标表=" + String.join(",", planTables) + "，SQL 实际表=" + String.join(",", sqlTables))
                        .build();
            }
        }

        // 2) 规划含筛选条件但 SQL 无 WHERE → 疑似丢失用户筛选意图，FAIL 供回退重生成。
        //    筛选直接改变结果集范围，比聚合/维度缺失更严重，故先于 WARN 检查判 FAIL。
        //    兜底：仅当筛选项含比较语义（=、in、like 等）才判定，避免解析噪声误 FAIL（保持尽力校验基调）。
        List<String> planFilters = plan.getFilters() == null ? new ArrayList<>() : plan.getFilters();
        if (!CollectionUtils.isEmpty(planFilters)
                && (feature.getWhereCount() == null || feature.getWhereCount() == 0)
                && hasComparableFilter(planFilters)) {
            String filterSummary = SqlTextUtil.truncate(String.join("；", planFilters), 200);
            return LevelResult.builder().level("L3").result("FAIL")
                    .detail("规划含筛选条件但 SQL 未体现 WHERE，疑似丢失用户筛选意图（规划筛选=" + filterSummary + "）")
                    .reason("规划筛选=" + filterSummary + "，SQL 实际 WHERE 条件数=0")
                    .build();
        }

        // 3) 指标/维度缺失尽力提示（WARN 不阻断）：仅提示是否缺少聚合/分组
        List<String> warnings = new ArrayList<>();
        boolean hasAggregate = feature.getAggregateColumns() != null && !feature.getAggregateColumns().isEmpty();
        boolean hasGroupBy = feature.getGroupByCount() != null && feature.getGroupByCount() > 0;
        if (!CollectionUtils.isEmpty(plan.getMetrics()) && !hasAggregate && !hasGroupBy) {
            warnings.add("规划要求指标聚合，但 SQL 未体现 GROUP BY/聚合函数，请核对口径");
        }
        if (!CollectionUtils.isEmpty(plan.getDimensions()) && !hasGroupBy) {
            warnings.add("规划含维度分组，但 SQL 未体现 GROUP BY，请核对是否需按维度拆分");
        }
        if (warnings.isEmpty()) {
            return LevelResult.builder().level("L3").result("PASS")
                    .detail("SQL 与规划语义基本一致（尽力校验）")
                    .reason("").build();
        }
        return LevelResult.builder().level("L3").result("WARN")
                .detail(String.join("；", warnings))
                .reason("L3 为尽力校验，WARN 不阻断执行，靠 L5 与反馈兜底").build();
    }

    /** 规划筛选项中是否至少有一项含比较语义（否则视为未识别到有效筛选，不判 FAIL）。 */
    private static boolean hasComparableFilter(List<String> filters) {
        for (String filter : filters) {
            if (filter != null && FILTER_COMPARISON.matcher(filter).find()) {
                return true;
            }
        }
        return false;
    }
}
