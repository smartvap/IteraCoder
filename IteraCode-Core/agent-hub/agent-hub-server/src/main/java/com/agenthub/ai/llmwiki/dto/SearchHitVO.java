package com.agenthub.ai.llmwiki.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 检索命中项
 */
public record SearchHitVO(
        String path,
        String title,
        String type,
        List<String> tags,
        String content,
        Double score,
        @JsonProperty("hit_channels") String hitChannels
) {
}
