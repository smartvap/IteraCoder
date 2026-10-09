package com.agenthub.ai.rcagent.retrieval.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 融合检索质量指标聚合查询（接口3 POST /api/v1/retrieval/log/stats，FR-007 P2）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalStatsQuery {

    /** 数据源过滤（不传则全部） */
    private String dsId;

    /** 开始时间（必填）yyyy-MM-dd HH:mm:ss */
    private String startTime;

    /** 结束时间（必填）yyyy-MM-dd HH:mm:ss */
    private String endTime;

    /** day/week（默认 day） */
    private String granularity;
}
