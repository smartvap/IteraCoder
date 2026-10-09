package com.agenthub.ai.rcagent.retrieval.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检索元信息（meta：channel_hits/context_item_count/context_token_estimate/space_used/latency_ms）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalMeta {

    /** 各路命中统计（channel 键：schema_tables/schema_columns/templates/biz_links/relations/metrics/concepts/wiki_docs） */
    private Map<String, Integer> channelHits = new LinkedHashMap<>();

    /** 输出上下文条目数 */
    private Integer contextItemCount = 0;

    /** 上下文 token 估算 */
    private Integer contextTokenEstimate = 0;

    /** 命中的知识库 B 空间名（未启用为 null） */
    private String spaceUsed;

    /** 单次检索耗时（毫秒） */
    private Long latencyMs = 0L;
}
