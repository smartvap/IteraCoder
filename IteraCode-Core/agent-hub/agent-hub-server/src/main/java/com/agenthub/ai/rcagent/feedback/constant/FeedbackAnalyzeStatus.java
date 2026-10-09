package com.agenthub.ai.rcagent.feedback.constant;

/**
 * 反馈分析阶段（fb_feedback_flow.analyze_status）。
 *
 * <p>data-model 数据字典 AnalyzeStatus：RECEIVED → DIFF_ANALYZING → SUGGESTING
 * → REVIEW_CREATED；diff 无法解析/置信过低落 MANUAL_REQUIRED，异常落 ANALYZE_FAILED。</p>
 */
public enum FeedbackAnalyzeStatus {

    /** 初始 */
    RECEIVED("RECEIVED"),
    /** 正在执行 AST diff（含 module-006 parse） */
    DIFF_ANALYZING("DIFF_ANALYZING"),
    /** 正在生成修正建议 */
    SUGGESTING("SUGGESTING"),
    /** diff 无法解析/LLM 归类置信过低，转人工判断（不自动建审/改知识） */
    MANUAL_REQUIRED("MANUAL_REQUIRED"),
    /** 审核任务已创建 */
    REVIEW_CREATED("REVIEW_CREATED"),
    /** 分析异常（重试或转人工） */
    ANALYZE_FAILED("ANALYZE_FAILED");

    private final String value;

    FeedbackAnalyzeStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (FeedbackAnalyzeStatus s : values()) {
            if (s.value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    public static FeedbackAnalyzeStatus from(String v) {
        if (v == null) {
            return null;
        }
        for (FeedbackAnalyzeStatus s : values()) {
            if (s.value.equals(v)) {
                return s;
            }
        }
        return null;
    }
}
