package com.agenthub.ai.rcagent.crosssource;

import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;
import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨源计划视图适配器（brownfield 适配层，module-003）。
 *
 * <p>把 {@link CrossSourceJoinPlan} 的**单源部分**（目标表 / 过滤 / 聚合）映射为既有
 * {@link SqlPlanView}，供 {@code SqlGuardRequest.plan} 使用，使 L3 语义对照在跨源场景下可用。
 * <b>不修改</b> 既有 {@code RcaPlanViewParser}；映射失败返回 {@code null}（该源 L3 → SKIP）。</p>
 */
@Slf4j
@Component
public class CrossSourcePlanViewAdapter {

    /** 单源计划视图中的过滤/指标单项长度上限 */
    private static final int ITEM_MAX_LEN = 200;

    /**
     * 映射单源部分为 SQL 计划只读视图。
     *
     * @param plan        跨源计划（可空）
     * @param sourceIndex 源序号（1/2）
     * @return 计划视图；计划为空 / 源序号无法定位 / 无可对照信息时返回 {@code null}（L3 SKIP）
     */
    public SqlPlanView from(CrossSourceJoinPlan plan, int sourceIndex) {
        if (plan == null || plan.getJoinType() == null) {
            return null;
        }
        boolean leftSide = plan.getLeftSourceIndex() != null && plan.getLeftSourceIndex() == sourceIndex;
        boolean rightSide = plan.getRightSourceIndex() != null && plan.getRightSourceIndex() == sourceIndex;
        if (!leftSide && !rightSide) {
            return null;
        }
        String table = leftSide ? plan.getLeftTable() : plan.getRightTable();
        List<String> filters = leftSide ? plan.getLeftFilters() : plan.getRightFilters();
        String aggregation = leftSide ? plan.getLeftAggregation() : plan.getRightAggregation();
        String dsId = leftSide ? plan.getLeftDsId() : plan.getRightDsId();
        // 无可对照信息时不构造视图，保持 L3 SKIP 旧行为（避免无依据的表不一致 FAIL）
        if (isBlank(table) && (filters == null || filters.isEmpty())) {
            return null;
        }
        SqlPlanView view = SqlPlanView.builder()
                .dsId(dsId)
                .targetTables(table == null || table.isBlank() ? new ArrayList<>() : List.of(table.trim()))
                .filters(sanitize(filters))
                .metrics(isBlank(aggregation) ? new ArrayList<>() : List.of(truncate(aggregation)))
                .dimensions(new ArrayList<>())
                .aggKernel(isBlank(aggregation) ? null : truncate(aggregation))
                .build();
        log.info("RCAgent 跨源计划视图映射: sourceIndex={}, filterCount={}, hasAggregation={}",
                sourceIndex, view.getFilters().size(), !isBlank(aggregation));
        return view;
    }

    private static List<String> sanitize(List<String> items) {
        List<String> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        for (String item : items) {
            if (item == null || item.isBlank()) {
                continue;
            }
            result.add(truncate(item));
        }
        return result;
    }

    private static String truncate(String text) {
        return text.length() <= ITEM_MAX_LEN ? text : text.substring(0, ITEM_MAX_LEN);
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
