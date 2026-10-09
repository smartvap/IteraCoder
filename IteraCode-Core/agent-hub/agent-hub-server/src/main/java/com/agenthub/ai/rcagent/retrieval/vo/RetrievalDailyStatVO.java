package com.agenthub.ai.rcagent.retrieval.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 检索质量按日/周聚合行（daily：bucket/search_count/cold_start_count/avg_latency_ms）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalDailyStatVO {

    /** 时间桶（day: yyyy-MM-dd；week: yyyy-ww） */
    private String bucket;

    /** 检索次数 */
    private Long searchCount = 0L;

    /** 冷启动次数 */
    private Long coldStartCount = 0L;

    /** 平均耗时（毫秒） */
    private Long avgLatencyMs = 0L;
}
