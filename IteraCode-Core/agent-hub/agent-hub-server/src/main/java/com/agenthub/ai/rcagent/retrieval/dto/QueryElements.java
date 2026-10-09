package com.agenthub.ai.rcagent.retrieval.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * 检索要素（query_elements）：意图要素（time_range/dimensions/metrics/entities/concepts/keywords）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class QueryElements {

    /** 时间范围（要素） */
    private TimeRange timeRange;

    /** 维度词 */
    private List<String> dimensions;

    /** 指标词 */
    private List<String> metrics;

    /** 实体词 */
    private List<String> entities;

    /** 业务概念候选 */
    private List<String> concepts;

    /** 自由关键词 */
    private List<String> keywords;

    /** 时间范围（time_range 子结构） */
    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class TimeRange {

        /** 开始（yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss） */
        private String start;

        /** 结束 */
        private String end;

        /** 粒度 day/month/year 等 */
        private String granularity;
    }
}
