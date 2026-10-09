package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 审核决策（data-model §5 ReviewDecision，rcagent_attribution_review_log.decision）。
 */
public enum ReviewDecision {

    /** 通过 */
    APPROVE,
    /** 打回 */
    REJECT;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (ReviewDecision t : values()) {
            if (t.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
