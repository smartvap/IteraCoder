package com.agenthub.ai.llmwiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * llm-wiki 知识空间视图（GET /spaces 等返回）
 */
public record SpaceVO(
        String name,
        List<String> types,
        @JsonProperty("collection_name") String collectionName,
        @JsonProperty("page_count") int pageCount,
        @JsonProperty("created_at") String createdAt
) {
}
