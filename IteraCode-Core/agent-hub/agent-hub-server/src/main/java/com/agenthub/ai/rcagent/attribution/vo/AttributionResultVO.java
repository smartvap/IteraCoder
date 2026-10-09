package com.agenthub.ai.rcagent.attribution.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 归因统一出参（analyze/followUp/decideReview，api-contract AttributionResultVO）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionResultVO {

    /** 任务基础信息 */
    private AttributionTaskVO task;

    /** 当前阶段：DONE / QUERY_REVIEWING / CONCLUSION_REVIEWING / DEGRADED / LIMIT_REACHED */
    private String stage;

    /** 异常判定摘要 */
    private AnomalyVO anomaly;

    /** 已下钻树 */
    private List<AttributionNodeVO> drillTree = new ArrayList<>();

    /** 主因 + 次要因素（摘要） */
    private List<AttributionFactorVO> mainFactors = new ArrayList<>();

    /** 全量候选因子（详情） */
    private List<AttributionFactorVO> factors = new ArrayList<>();

    /** 归因查询审计（详情） */
    private List<AttributionQueryVO> queries = new ArrayList<>();

    /** 结构化结论 */
    private ConclusionVO conclusion;

    /** 自然语言结论摘要 */
    private String summary;

    /** 待审项（stage 为 *REVIEWING 时有值） */
    private PendingReviewVO pendingReview;

    /** 降级/限制信息（DEGRADED/LIMIT_REACHED） */
    private DegradedVO degraded;

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class DegradedVO {

        /** 是否有 verified 指标口径（BR-001） */
        private Boolean metricDefined;

        /** 降级原因/已达上限说明 */
        private String reason;

        /** 已达下钻深度 */
        private Integer depthReached;

        /** 已用查询数 */
        private Integer queriesUsed;
    }

    public BigDecimal confidenceOrZero() {
        return conclusion == null || conclusion.getConfidence() == null
                ? BigDecimal.ZERO : conclusion.getConfidence();
    }
}
