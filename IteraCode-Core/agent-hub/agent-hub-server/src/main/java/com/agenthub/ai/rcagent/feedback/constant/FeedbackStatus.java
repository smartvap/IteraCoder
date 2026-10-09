package com.agenthub.ai.rcagent.feedback.constant;

/**
 * 反馈闭环状态机（fb_feedback_flow.status）。
 *
 * <p>data-model 数据字典 FeedbackStatus：RECEIVED → ANALYZING → REVIEWING →
 * APPROVED/REJECTED → WRITTEN_BACK → CLOSED，分析/回写异常可落 FAILED 重试。</p>
 */
public enum FeedbackStatus {

    /** 已接收待分析 */
    RECEIVED("RECEIVED"),
    /** 分析中（diff/类型/建议） */
    ANALYZING("ANALYZING"),
    /** 已建审核任务待人工 */
    REVIEWING("REVIEWING"),
    /** 审核通过待回写 */
    APPROVED("APPROVED"),
    /** 审核拒绝（不写知识） */
    REJECTED("REJECTED"),
    /** 回写中（A/B） */
    WRITTEN_BACK("WRITTEN_BACK"),
    /** 闭环完成 */
    CLOSED("CLOSED"),
    /** 分析/回写失败（可重试） */
    FAILED("FAILED");

    private final String value;

    FeedbackStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (FeedbackStatus s : values()) {
            if (s.value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    public static FeedbackStatus from(String v) {
        if (v == null) {
            return null;
        }
        for (FeedbackStatus s : values()) {
            if (s.value.equals(v)) {
                return s;
            }
        }
        return null;
    }
}
