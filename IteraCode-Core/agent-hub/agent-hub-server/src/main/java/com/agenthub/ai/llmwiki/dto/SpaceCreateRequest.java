package com.agenthub.ai.llmwiki.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 知识空间创建请求（POST /spaces）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SpaceCreateRequest(
        String name,
        List<String> types
) {
}
