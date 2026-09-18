package com.agenthub.ai.llmwiki.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 增量同步结果（POST /pages/sync）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncResultVO(
        String space,
        List<String> added,
        List<String> updated,
        List<String> deleted,
        Integer skipped,
        List<String> errors,
        @JsonProperty("skipped_extensions") List<String> skippedExtensions
) {
}
