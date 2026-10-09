package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 查询人工审核状态（data-model §5 QueryReviewStatus，rcagent_attribution_query_log.review_status）。
 */
public enum QueryReviewStatus {

    /** 无需审核（LOW 已放行） */
    NONE,
    /** 已人工放行 */
    APPROVED,
    /** 已人工打回 */
    REJECTED;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (QueryReviewStatus t : values()) {
            if (t.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
