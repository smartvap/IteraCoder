package com.agenthub.ai.rcagent.attribution.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构化归因结论（api-contract data.conclusion，data-model §4.4 conclusion_json）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ConclusionVO {

    /** 指标名 */
    private String metric;

    /** 异常方向 */
    private String direction;

    /** 本期总体值 */
    private BigDecimal curTotal;

    /** 基期总体值 */
    private BigDecimal baseTotal;

    /** 总体变化量 */
    private BigDecimal delta;

    /** 主因 */
    private FactorBrief mainFactor;

    /** 次要因素 */
    private List<FactorBrief> secondaryFactors = new ArrayList<>();

    /** 新出现段 */
    private List<String> newSegments = new ArrayList<>();

    /** 消失段 */
    private List<String> disappearedSegments = new ArrayList<>();

    /** 证据链 */
    private List<EvidenceChainItem> evidenceChain = new ArrayList<>();

    /** 置信度（0~1） */
    private BigDecimal confidence;

    /** 建议行动 */
    private List<String> actionSuggestions = new ArrayList<>();

    /** 敏感提示（null=无） */
    private String sensitiveNote;

    /**
     * 因子摘要（conclusion_json 子结构）。
     */
    @Data
    public static class FactorBrief {

        private String factorId;

        private String dimension;

        private String dimensionLabel;

        private String segmentKey;

        private String segmentValue;

        private BigDecimal deltaValue;

        private BigDecimal contributionRatio;

        private String hypothesis;
    }
}
