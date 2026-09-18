package com.agenthub.ai.llmwiki.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * llm-wiki 健康状态
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthVO(
        String status,
        @JsonProperty("embedding_ready") Boolean embeddingReady,
        Integer spaces,
        @JsonProperty("chroma_ready") Boolean chromaReady,
        List<String> formats
) {
}
