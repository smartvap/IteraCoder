package com.agenthub.ai.rcagent.attribution.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 下钻层内对比摘要（compare_summary_json，data-model §4.3）。
 */
@Data
public class DrillNodeSummary {

    /** 本期总体值 */
    private BigDecimal curTotal;

    /** 基期总体值 */
    private BigDecimal baseTotal;

    /** 总体变化量 */
    private BigDecimal delta;

    /** 总体变化率 % */
    private BigDecimal deltaRatio;

    /** 参与拆解的段数量 */
    private Integer segmentCount;

    /** topN 段（贡献占比降序） */
    private List<TopSegment> topSegments;

    @Data
    public static class TopSegment {

        /** 维度 */
        private String dimension;

        /** 段键 */
        private String segmentKey;

        /** 段展示值 */
        private String segmentValue;

        /** 本期值 */
        private BigDecimal cur;

        /** 基期值 */
        private BigDecimal base;

        /** 段变化量 */
        private BigDecimal delta;

        /** 贡献占比 % */
        private BigDecimal contributionRatio;

        /** 是否主因 */
        private boolean main;
    }

    /** 从查询结果 rows（key → 聚合列名）提取单值总体值，宽松解析 */
    public static BigDecimal totalOf(List<Map<String, Object>> rows, String valueColumn) {
        if (rows == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Map<String, Object> first = rows.get(0);
        if (first == null || first.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Object value = valueColumn == null ? first.values().iterator().next() : first.get(valueColumn);
        return toDecimal(value);
    }

    public static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }
}
