package com.agenthub.ai.llmwiki.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 知识页视图（GET /pages/{path} 返回，含正文与元数据）
 * 列表接口（GET /pages）返回不含 content/content_hash 的轻量对象。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PageVO(
        String path,
        String title,
        String type,
        List<String> tags,
        String content,
        @JsonProperty("content_hash") String contentHash,
        String status,
        @JsonProperty("chunk_count") Integer chunkCount,
        @JsonProperty("updated_at") String updatedAt
) {
}
