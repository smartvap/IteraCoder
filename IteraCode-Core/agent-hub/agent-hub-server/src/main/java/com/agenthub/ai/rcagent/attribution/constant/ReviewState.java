package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 审核阶段（data-model §5 ReviewState，rcagent_attribution_task.review_state）。
 */
public enum ReviewState {

    /** 无需/未进入人工审核 */
    NONE,
    /** 下钻查询 MED/HIGH 等待放行 */
    QUERY_REVIEWING,
    /** 高价值/敏感结论等待确认 */
    CONCLUSION_REVIEWING;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (ReviewState s : values()) {
            if (s.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
