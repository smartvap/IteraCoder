package com.agenthub.ai.rcagent.attribution.component;

import com.agenthub.ai.rcagent.attribution.constant.FactorType;
import com.agenthub.ai.rcagent.attribution.constant.SegmentChange;
import com.agenthub.ai.rcagent.attribution.model.DrillNodeSummary;
import com.agenthub.ai.rcagent.attribution.model.SegmentContribution;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 贡献拆解算法（ContributionCalculator，FR-003/FR-BE-003）。
 *
 * <p>段 delta = cur - base；贡献占比分母可配（positiveMajority=true 按同向 delta 和，
 * false 按 |delta| 总和）；输出新出现/消失子群与结构变化信号，按贡献度排序并标记主因。</p>
 */
@Component
public class ContributionCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal RATIO_SCALE_PERCENT = new BigDecimal("1");

    /**
     * 对本期/基期分组查询结果做贡献拆解。
     *
     * @param curRows   本期分组行（segment_key → metric_value）
     * @param baseRows  基期分组行
     * @param curTotal  本期总体（分母/占比基准）
     * @param baseTotal 基期总体
     * @param dimension 维度名
     * @param dimensionLabel 维度展示名
     * @param evidenceQueryIds 本层证据查询（group_cur/group_base）
     * @param positiveMajority true=分母用同向 delta 和；false=绝对值总和
     * @param topN       返回 topN（0/null 全量）
     */
    public List<SegmentContribution> compute(List<Map<String, Object>> curRows,
                                             List<Map<String, Object>> baseRows,
                                             BigDecimal curTotal, BigDecimal baseTotal,
                                             String dimension, String dimensionLabel,
                                             List<String> evidenceQueryIds,
                                             boolean positiveMajority, Integer topN) {
        List<SegmentContribution> result = new ArrayList<>();
        Map<String, Map<String, Object>> curMap = indexBySegmentKey(curRows);
        Map<String, Map<String, Object>> baseMap = indexBySegmentKey(baseRows);
        BigDecimal curT = curTotal == null ? BigDecimal.ZERO : curTotal;
        BigDecimal baseT = baseTotal == null ? BigDecimal.ZERO : baseTotal;
        BigDecimal totalDelta = curT.subtract(baseT);
        int totalSign = totalDelta.signum();

        // 合并键集
        Map<String, SegmentContribution> merged = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : curMap.entrySet()) {
            SegmentContribution sc = new SegmentContribution();
            sc.setDimension(dimension);
            sc.setDimensionLabel(dimensionLabel);
            sc.setSegmentKey(e.getKey());
            sc.setSegmentValue(displayValue(e.getValue()));
            sc.setCurValue(toDecimal(rowValue(e.getValue(), "metric_value")));
            sc.setBaseValue(BigDecimal.ZERO);
            sc.setEvidenceQueryIds(evidenceQueryIds);
            merged.put(e.getKey(), sc);
        }
        for (Map.Entry<String, Map<String, Object>> e : baseMap.entrySet()) {
            SegmentContribution sc = merged.get(e.getKey());
            if (sc == null) {
                sc = new SegmentContribution();
                sc.setDimension(dimension);
                sc.setDimensionLabel(dimensionLabel);
                sc.setSegmentKey(e.getKey());
                sc.setSegmentValue(displayValue(e.getValue()));
                sc.setCurValue(BigDecimal.ZERO);
                sc.setEvidenceQueryIds(evidenceQueryIds);
                merged.put(e.getKey(), sc);
            }
            sc.setBaseValue(toDecimal(rowValue(e.getValue(), "metric_value")));
        }

        for (SegmentContribution sc : merged.values()) {
            BigDecimal delta = sc.getCurValue().subtract(sc.getBaseValue());
            sc.setDeltaValue(delta);
            sc.setContribution(delta);
            sc.setDeltaRatio(ratio(delta, sc.getBaseValue()));
            sc.setCurShare(share(sc.getCurValue(), curT));
            sc.setBaseShare(share(sc.getBaseValue(), baseT));
            classify(sc);
            result.add(sc);
        }

        // 排序并计算贡献占比（正贡献占比分母策略）
        result.sort(Comparator.comparing(s -> s.getDeltaValue().abs(), Comparator.reverseOrder()));
        BigDecimal denominator = denominator(result, positiveMajority, totalSign);
        if (denominator != null && denominator.signum() > 0) {
            for (SegmentContribution sc : result) {
                sc.setContributionRatio(sc.getDeltaValue().abs().multiply(HUNDRED)
                        .divide(denominator, 4, RoundingMode.HALF_UP));
            }
        }
        // rank + 主因（rank1 且贡献占比显著）
        int rank = 1;
        for (SegmentContribution sc : result) {
            sc.setRank(rank);
            if (rank == 1 && sc.getContributionRatio() != null && sc.getContributionRatio().compareTo(RATIO_SCALE_PERCENT) >= 0) {
                sc.setMain(true);
            }
            rank++;
        }
        int limit = topN == null || topN <= 0 ? result.size() : Math.min(topN, result.size());
        return new ArrayList<>(result.subList(0, limit));
    }

    private void classify(SegmentContribution sc) {
        int curSign = sc.getCurValue().signum();
        int baseSign = sc.getBaseValue().signum();
        if (curSign > 0 && baseSign == 0) {
            sc.setSegmentChange(SegmentChange.NEW.name());
            sc.setFactorType(FactorType.NEW_SEGMENT.name());
            return;
        }
        if (curSign == 0 && baseSign > 0) {
            sc.setSegmentChange(SegmentChange.DISAPPEARED.name());
            sc.setFactorType(FactorType.DISAPPEARED_SEGMENT.name());
            return;
        }
        int deltaSign = sc.getDeltaValue().signum();
        if (deltaSign > 0) {
            sc.setSegmentChange(SegmentChange.INCREASE.name());
        } else if (deltaSign < 0) {
            sc.setSegmentChange(SegmentChange.DECREASE.name());
        } else {
            sc.setSegmentChange(SegmentChange.STABLE.name());
        }
        // 结构变化显著偏移（MVP 阈值 20pct）
        if (sc.getCurShare() != null && sc.getBaseShare() != null
                && sc.getCurShare().subtract(sc.getBaseShare()).abs().doubleValue() >= 20.0) {
            sc.setFactorType(FactorType.STRUCTURAL_CHANGE.name());
            return;
        }
        sc.setFactorType(FactorType.SEGMENT_CONTRIB.name());
    }

    private BigDecimal denominator(List<SegmentContribution> list, boolean positiveMajority, int totalSign) {
        if (positiveMajority) {
            BigDecimal sum = BigDecimal.ZERO;
            for (SegmentContribution sc : list) {
                int sign = sc.getDeltaValue().signum();
                if (sign == 0) {
                    continue;
                }
                // 分母=同向贡献和（方向与总体变化一致的部分）
                if (totalSign == 0 || sign == totalSign) {
                    sum = sum.add(sc.getDeltaValue().abs());
                }
            }
            return sum;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (SegmentContribution sc : list) {
            sum = sum.add(sc.getDeltaValue().abs());
        }
        return sum;
    }

    private BigDecimal ratio(BigDecimal delta, BigDecimal base) {
        if (delta == null || base == null || base.signum() == 0) {
            return null;
        }
        return delta.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal share(BigDecimal value, BigDecimal total) {
        if (value == null || total == null || total.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return value.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }

    private Map<String, Map<String, Object>> indexBySegmentKey(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        if (rows == null) {
            return map;
        }
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            Object key = rowValue(row, "segment_key");
            if (key == null) {
                continue;
            }
            map.put(String.valueOf(key), row);
        }
        return map;
    }

    private String displayValue(Map<String, Object> row) {
        Object segmentValue = rowValue(row, "segment_value");
        if (segmentValue != null) {
            return String.valueOf(segmentValue);
        }
        Object key = rowValue(row, "segment_key");
        return key == null ? "" : String.valueOf(key);
    }

    /** 列值宽松读取（忽略大小写） */
    private Object rowValue(Map<String, Object> row, String column) {
        if (row == null || column == null) {
            return null;
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            if (e.getKey().equalsIgnoreCase(column)) {
                return e.getValue();
            }
        }
        return null;
    }

    private BigDecimal toDecimal(Object value) {
        return DrillNodeSummary.toDecimal(value);
    }
}
