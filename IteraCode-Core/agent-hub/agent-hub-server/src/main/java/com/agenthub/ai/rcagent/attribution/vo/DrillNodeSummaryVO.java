package com.agenthub.ai.rcagent.attribution.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 下钻节点对比摘要 VO（compare_summary_json 视图）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class DrillNodeSummaryVO {

    private BigDecimal curTotal;

    private BigDecimal baseTotal;

    private BigDecimal delta;

    private BigDecimal deltaRatio;

    private Integer segmentCount;

    private List<TopSegmentVO> topSegments;

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class TopSegmentVO {

        private String dimension;

        private String segmentKey;

        private String segmentValue;

        private BigDecimal cur;

        private BigDecimal base;

        private BigDecimal delta;

        private BigDecimal contributionRatio;

        private boolean main;
    }
}
