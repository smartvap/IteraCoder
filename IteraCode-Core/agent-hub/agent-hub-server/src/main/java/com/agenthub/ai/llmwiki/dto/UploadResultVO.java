package com.agenthub.ai.llmwiki.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 文档上传结果（POST /pages/upload）
 * <p>
 * 对应 llm-wiki 返回：{path, content_hash, chunk_count, created}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UploadResultVO(
        String path,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("chunk_count") Integer chunkCount,
        Boolean created
) {
}
