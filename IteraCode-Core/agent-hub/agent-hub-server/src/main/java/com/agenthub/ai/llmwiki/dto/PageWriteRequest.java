package com.agenthub.ai.llmwiki.dto;

/**
 * 知识页写入请求（POST/PUT /pages）
 * PUT 时以 URL path 为准，body.path 可忽略。
 */
public record PageWriteRequest(
        String path,
        String content
) {
}
