package com.agenthub.ai.rcagent.feedback.constant;

/**
 * 反馈类型（fb_feedback_flow.feedback_type，与 module-004 FeedbackType 一致）。
 */
public enum FeedbackType {

    /** 正确性肯定 */
    POSITIVE("POSITIVE"),
    /** 正确性否定/纠错意图 */
    NEGATIVE("NEGATIVE"),
    /** SQL 修正 */
    CORRECTION("CORRECTION"),
    /** 业务口径/解释补充 */
    EXPLANATION("EXPLANATION");

    private final String value;

    FeedbackType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (FeedbackType t : values()) {
            if (t.value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    public static FeedbackType from(String v) {
        if (v == null) {
            return null;
        }
        for (FeedbackType t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        return null;
    }
}
