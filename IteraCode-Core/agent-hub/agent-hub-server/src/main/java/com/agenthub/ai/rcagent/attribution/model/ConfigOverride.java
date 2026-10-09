package com.agenthub.ai.rcagent.attribution.model;

import com.agenthub.ai.rcagent.attribution.config.AttributionProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 请求级规则覆盖（api-contract analyze/follow-up config_overrides，BR-004 可配置展示）。
 *
 * <p>只允许在配置默认值界内覆盖：阈值 0~1000、maxDepth 1~10、maxQueries 1~200。</p>
 *
 * <p>该类为 analyze/follow-up 请求的嵌套值对象：外层 DTO 已标注 snake_case 命名策略，
 * 但 {@code @JsonNaming} 不作用于内层类型，需在本类补充相同策略，保证
 * threshold_rate/min_delta_abs/max_depth/max_queries/top_segments/positive_majority/mvp_review_all 契约字段可绑定。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ConfigOverride {

    /** 环比/同比波动阈值覆盖（%） */
    private Double thresholdRate;

    /** 最小绝对变化覆盖 */
    private Double minDeltaAbs;

    /** 下钻深度上限覆盖 */
    private Integer maxDepth;

    /** 查询次数上限覆盖 */
    private Integer maxQueries;

    /** topN 段覆盖 */
    private Integer topSegments;

    /** 贡献占比分母覆盖（true=同向总变化） */
    private Boolean positiveMajority;

    /** MVP 全量审核开关覆盖 */
    private Boolean mvpReviewAll;

    /** 将覆盖值套用到默认配置上（空值保留默认），返回本次生效配置视图 */
    public EffectiveConfig apply(AttributionProperties defaults) {
        EffectiveConfig cfg = new EffectiveConfig();
        cfg.setMomThresholdRate(thresholdRate == null ? defaults.momThresholdRateValue() : thresholdRate);
        cfg.setYoyThresholdRate(cfg.getMomThresholdRate());
        cfg.setMinDeltaAbs(minDeltaAbs == null ? defaults.minDeltaAbsValue() : minDeltaAbs);
        cfg.setMaxDepth(maxDepth == null ? defaults.maxDepthValue() : Math.min(Math.max(maxDepth, 1), 10));
        cfg.setMaxQueries(maxQueries == null ? defaults.maxQueriesValue() : Math.min(Math.max(maxQueries, 1), 200));
        cfg.setTopSegments(topSegments == null ? defaults.topSegmentsValue()
                : Math.min(Math.max(topSegments, 1), 200));
        cfg.setPositiveMajority(positiveMajority == null ? defaults.positiveMajorityValue() : positiveMajority);
        cfg.setMvpReviewAll(mvpReviewAll == null ? defaults.mvpReviewAllValue() : mvpReviewAll);
        cfg.setHighReviewRequired(defaults.highReviewRequiredValue());
        return cfg;
    }

    /**
     * 本次归因生效的规则配置（内部快照，避免重复读 yml/覆盖对象）。
     */
    @Data
    public static class EffectiveConfig {

        private double momThresholdRate;
        private double yoyThresholdRate;
        private double minDeltaAbs;
        private int maxDepth;
        private int maxQueries;
        private int topSegments;
        private boolean positiveMajority;
        private boolean mvpReviewAll;
        private boolean highReviewRequired;
    }
}
