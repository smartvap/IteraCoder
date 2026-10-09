package com.agenthub.ai.rcagent.attribution.component;

import com.agenthub.ai.rcagent.attribution.model.ConfigOverride;
import org.springframework.stereotype.Component;

/**
 * 审核在环门闸（ReviewCoordinator，FR-007/FR-BE-007）。
 *
 * <p>MED/HIGH 归因查询进入 QUERY_REVIEWING；敏感/高风险/口径未 verified/低置信结论进入
 * CONCLUSION_REVIEWING（BR-003/BR-001）。只负责"是否入审"判定，不驱动状态流转。</p>
 */
@Component
public class ReviewCoordinator {

    /**
     * 归因查询是否需人工放行。
     *
     * @param riskLevel module-006 风险分级（BLOCK/LOW/MEDIUM/HIGH）
     */
    public boolean queryNeedsReview(String riskLevel, ConfigOverride.EffectiveConfig cfg) {
        if (riskLevel == null) {
            return false;
        }
        if ("MEDIUM".equals(riskLevel) || "HIGH".equals(riskLevel)) {
            return true;
        }
        // MVP 调试期全量审核开关：LOW 也入审（审核通过后以 requireManualApproval=false 仍可自动执行）
        return cfg.isMvpReviewAll() && "LOW".equals(riskLevel);
    }

    /**
     * 归因结论是否需人工确认。
     *
     * @param sensitive        是否命中敏感归因
     * @param metricVerified   指标口径是否 verified
     * @param confidence       结论置信度（0~1）
     * @param severity         异常严重度（HIGH/MEDIUM/LOW）
     */
    public ConclusionReviewReason conclusionNeedsReview(boolean sensitive, boolean metricVerified,
                                                        java.math.BigDecimal confidence, String severity,
                                                        ConfigOverride.EffectiveConfig cfg) {
        if (sensitive) {
            return ConclusionReviewReason.of(true, "SENSITIVE", "命中敏感归因（个人/群体/敏感维度），强制人工确认");
        }
        if (!metricVerified) {
            return ConclusionReviewReason.of(true, "NOT_VERIFIED", "指标无 verified 口径，不输出高置信结论（BR-001）");
        }
        if (confidence != null && confidence.doubleValue() < 0.6) {
            return ConclusionReviewReason.of(true, "LOW_CONFIDENCE", "结论置信度低于阈值，需人工确认");
        }
        if (cfg.isHighReviewRequired() && "HIGH".equals(severity)) {
            return ConclusionReviewReason.of(true, "HIGH_SEVERITY", "高严重度异常结论，需人工确认");
        }
        if (cfg.isMvpReviewAll()) {
            return ConclusionReviewReason.of(true, "MVP_REVIEW_ALL", "MVP 联调全量审核开关开启");
        }
        return ConclusionReviewReason.of(false, null, null);
    }

    /** 结论审核原因（可空 no-review） */
    public static class ConclusionReviewReason {

        private final boolean needsReview;
        private final String reasonCode;
        private final String reasonText;

        private ConclusionReviewReason(boolean needsReview, String reasonCode, String reasonText) {
            this.needsReview = needsReview;
            this.reasonCode = reasonCode;
            this.reasonText = reasonText;
        }

        static ConclusionReviewReason of(boolean needsReview, String reasonCode, String reasonText) {
            return new ConclusionReviewReason(needsReview, reasonCode, reasonText);
        }

        public boolean isNeedsReview() {
            return needsReview;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public String getReasonText() {
            return reasonText;
        }
    }
}
