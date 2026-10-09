package com.agenthub.ai.rcagent.attribution.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 归因规则配置视图（GET /api/v1/attribution/config，api-contract 接口 8）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionConfigVO {

    /** 异常识别阈值 */
    private AnomalyConfig anomaly = new AnomalyConfig();

    /** 下钻上限 */
    private DrillConfig drill = new DrillConfig();

    /** 贡献算法 */
    private ContributionConfig contribution = new ContributionConfig();

    /** 审核开关 */
    private ReviewConfig review = new ReviewConfig();

    @Data
    public static class AnomalyConfig {

        private Double momThresholdRate;
        private Double yoyThresholdRate;
        private Double minDeltaAbs;
        private Double continuityThreshold;
    }

    @Data
    public static class DrillConfig {

        private Integer maxDepth;
        private Integer maxQueries;
        private Integer topSegments;
    }

    @Data
    public static class ContributionConfig {

        private Boolean positiveMajority;
    }

    @Data
    public static class ReviewConfig {

        private Boolean mvpReviewAll;
        private Boolean highReviewRequired;
    }
}
