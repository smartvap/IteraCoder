package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 审核对象（data-model §5 ReviewObject，rcagent_attribution_review_log.review_object）。
 */
public enum ReviewObject {

    /** 归因 SQL 放行审核 */
    QUERY,
    /** 归因结论审核 */
    CONCLUSION;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (ReviewObject t : values()) {
            if (t.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
