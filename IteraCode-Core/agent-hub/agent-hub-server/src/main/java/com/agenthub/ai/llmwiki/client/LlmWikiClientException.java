package com.agenthub.ai.llmwiki.client;

import lombok.Getter;

/**
 * llm-wiki 调用异常（连接失败 / 非 2xx 响应）
 */
@Getter
public class LlmWikiClientException extends RuntimeException {

    /** HTTP 状态码；-1 表示连接/网络异常 */
    private final int statusCode;

    /** llm-wiki 返回的错误体（可能为 null） */
    private final String responseBody;

    public LlmWikiClientException(int statusCode, String responseBody, String message) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public LlmWikiClientException(int statusCode, String responseBody, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }
}
