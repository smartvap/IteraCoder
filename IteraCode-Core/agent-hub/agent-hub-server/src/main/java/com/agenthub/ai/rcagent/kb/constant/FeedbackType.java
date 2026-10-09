package com.agenthub.ai.rcagent.kb.constant;

/**
 * 反馈类型（data-model 数据字典 FeedbackType）。
 */
public enum FeedbackType {

    POSITIVE("POSITIVE"),
    NEGATIVE("NEGATIVE"),
    CORRECTION("CORRECTION"),
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
}
