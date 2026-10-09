package com.agenthub.ai.rcagent.kb.constant;

/**
 * 审核任务状态（data-model 数据字典 ReviewTaskStatus）。
 */
public enum KbReviewTaskStatus {

    /** 待审 */
    PENDING("pending"),
    /** 已批准（联动目标 active/confidence=1.0） */
    APPROVED("approved"),
    /** 已拒绝（附意见，保持原态或置 deprecated） */
    REJECTED("rejected");

    private final String value;

    KbReviewTaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (KbReviewTaskStatus s : values()) {
            if (s.value.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
