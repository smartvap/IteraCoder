package com.agenthub.ai.llmwiki.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * 检索请求（POST /search）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchRequest(
        String query,
        @JsonProperty("top_k") Integer topK,
        Map<String, String> filters
) {
}
