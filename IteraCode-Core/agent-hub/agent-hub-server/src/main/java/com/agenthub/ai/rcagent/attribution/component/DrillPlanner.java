package com.agenthub.ai.rcagent.attribution.component;

import com.agenthub.ai.rcagent.attribution.model.DrillPlanStep;
import com.agenthub.ai.rcagent.attribution.model.MetricKnowledgeSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 候选维度推断与下钻路径规划（DrillPlanner，FR-002/BR-004）。
 *
 * <p>从用户候选维度 + 指标口径 related_columns + 业务概念/schema 语义推断可下钻维度：
 * 语义码（BRANCH/TIME_SLOT/BIZ_TYPE/CHANNEL/REGION 等）映射为物理列候选，
 * 或直接在 related_columns 中匹配；找不到物理列的维度返回 column=null（由上层跳过并提示口径）。
 * 单次计划按 maxDepth 截断、按已下钻路径去重（FR-008 防重复归因）。</p>
 */
@Component
public class DrillPlanner {

    /** 维度语义码 → 候选物理列（忽略大小写），来源 data-model §5 Dimension 候选维度 */
    private static final Map<String, String[]> DIMENSION_COLUMN_HINTS = new LinkedHashMap<>();

    static {
        DIMENSION_COLUMN_HINTS.put("BRANCH", new String[]{"branch_id", "branch_no", "branch_code", "office_id", "store_id", "dept_id"});
        DIMENSION_COLUMN_HINTS.put("TIME_SLOT", new String[]{"time_slot", "time_bucket", "hour_bucket", "period", "shift"});
        DIMENSION_COLUMN_HINTS.put("BUSINESS_TYPE", new String[]{"business_type", "biz_type", "product_id", "product_type", "complaint_type", "service_type"});
        DIMENSION_COLUMN_HINTS.put("CHANNEL", new String[]{"channel", "channel_id", "channel_type", "source", "source_type"});
        DIMENSION_COLUMN_HINTS.put("REGION", new String[]{"region", "region_code", "province", "city", "area_code"});
        DIMENSION_COLUMN_HINTS.put("CALIBER", new String[]{"caliber", "stat_type", "statistic_type"});
    }

    /**
     * 生成下钻计划。
     *
     * @param userDimensions 用户候选维度（为空则按口径自动推断）
     * @param snapshot       指标口径快照（related_columns 提供字段候选）
     * @param maxDepth       剩余可下钻深度上限
     * @param drilled        已下钻维度（避免重复归因）
     */
    public List<DrillPlanStep> plan(List<String> userDimensions, MetricKnowledgeSnapshot snapshot,
                                    int maxDepth, List<String> drilled) {
        List<DrillPlanStep> steps = new ArrayList<>();
        if (snapshot == null) {
            return steps;
        }
        List<String> dimensions = userDimensions == null || userDimensions.isEmpty()
                ? inferDimensions(snapshot)
                : new ArrayList<>(userDimensions);
        if (dimensions.isEmpty()) {
            return steps;
        }
        int budget = Math.max(0, maxDepth);
        for (String dimension : dimensions) {
            if (budget <= 0) {
                break;
            }
            if (dimension == null || dimension.isBlank()) {
                continue;
            }
            String dim = dimension.trim();
            if (drilled != null && containsIgnoreCase(drilled, dim)) {
                continue;
            }
            DrillPlanStep step = buildStep(dim, snapshot);
            if (step == null) {
                continue;
            }
            steps.add(step);
            budget--;
        }
        return steps;
    }

    /** 按口径 related_columns 推断候选维度（语义码反向匹配，保持 hints 顺序） */
    public List<String> inferDimensions(MetricKnowledgeSnapshot snapshot) {
        List<String> inferred = new ArrayList<>();
        if (snapshot == null || snapshot.getRelatedColumns() == null) {
            return inferred;
        }
        List<String> columns = snapshot.getRelatedColumns();
        for (Map.Entry<String, String[]> entry : DIMENSION_COLUMN_HINTS.entrySet()) {
            for (String hint : entry.getValue()) {
                if (snapshot.matchColumn(hint) != null) {
                    inferred.add(entry.getKey());
                    break;
                }
            }
        }
        return inferred;
    }

    /**
     * 构造单步：dimension 可为语义码、口径字段名或自定义列名。
     *
     * @return column=null 表示口径中找不到可分组物理列（跳过该维度）
     */
    public DrillPlanStep buildStep(String dimension, MetricKnowledgeSnapshot snapshot) {
        String dim = dimension.trim();
        DrillPlanStep step = new DrillPlanStep();
        step.setDimension(dim);
        step.setDimensionLabel(dim);
        String column = null;
        String source = "USER_DIMENSION";
        String[] hints = DIMENSION_COLUMN_HINTS.get(dim.toUpperCase(Locale.ROOT));
        if (hints != null) {
            column = snapshot.matchColumn(hints);
            source = "SCHEMA_HINT";
            if (column != null) {
                step.setDimensionLabel(dim);
            }
        }
        if (column == null) {
            // 直接作为字段名尝试（口径 related_columns 精确匹配优先）
            column = snapshot.matchColumn(dim);
            if (column == null && MetricKnowledgeSnapshot.bareColumn(dim) != null) {
                column = dim;
            }
            source = "USER_DIMENSION";
        }
        if (column == null) {
            // 口径字段反向扫描：字段名与用户维度名完全一致
            for (String c : safeColumns(snapshot)) {
                if (c.equalsIgnoreCase(dim)) {
                    column = c;
                    break;
                }
            }
        }
        step.setColumn(column);
        step.setSource(column == null ? null : source);
        return step;
    }

    /** 便捷：带 focus 子群的交叉下钻步骤（追问场景换维度） */
    public DrillPlanStep planWithFocus(String dimension, MetricKnowledgeSnapshot snapshot,
                                       String focusSegmentKey, String focusSegmentValue) {
        DrillPlanStep step = buildStep(dimension, snapshot);
        if (step != null) {
            step.setFocusSegmentKey(focusSegmentKey);
            step.setFocusSegmentValue(focusSegmentValue);
        }
        return step;
    }

    private List<String> safeColumns(MetricKnowledgeSnapshot snapshot) {
        List<String> list = new ArrayList<>();
        if (snapshot.getRelatedColumns() != null) {
            for (String c : snapshot.getRelatedColumns()) {
                if (c == null) {
                    continue;
                }
                String bare = MetricKnowledgeSnapshot.bareColumn(c);
                if (bare != null && !bare.isBlank()) {
                    list.add(bare);
                }
            }
        }
        return list;
    }

    private boolean containsIgnoreCase(List<String> list, String value) {
        if (list == null) {
            return false;
        }
        for (String s : list) {
            if (s != null && s.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
