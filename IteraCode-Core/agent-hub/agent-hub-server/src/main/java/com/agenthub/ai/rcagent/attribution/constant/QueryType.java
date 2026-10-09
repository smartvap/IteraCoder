package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 归因查询类型（data-model §5 QueryType，rcagent_attribution_query_log.query_type）。
 */
public enum QueryType {

    /** 总体值查询（指标 cur/base） */
    OVERALL,
    /** 时间趋势/序列查询 */
    TREND,
    /** 基期对照查询 */
    BASELINE,
    /** 本期分组下钻 SQL */
    GROUP_CUR,
    /** 基期分组对照 SQL */
    GROUP_BASE,
    /** 多维交叉分组 SQL */
    CROSS_GROUP,
    /** 数据质量/口径探针 SQL */
    QUALITY_PROBE;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (QueryType t : values()) {
            if (t.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
