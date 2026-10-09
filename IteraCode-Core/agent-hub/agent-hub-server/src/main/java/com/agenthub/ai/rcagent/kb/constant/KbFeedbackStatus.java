package com.agenthub.ai.rcagent.kb.constant;

/**
 * 反馈记录处理状态（data-model 数据字典 KbFeedbackStatus，feedback-loop 推进）。
 */
public enum KbFeedbackStatus {

    PENDING("PENDING"),
    ANALYZING("ANALYZING"),
    REVIEWING("REVIEWING"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    WRITTEN_BACK("WRITTEN_BACK"),
    CLOSED("CLOSED");

    private final String value;

    KbFeedbackStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (KbFeedbackStatus s : values()) {
            if (s.value.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
