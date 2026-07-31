package com.agenthub.ai.base.pojo.vo;

import lombok.Data;

@Data
public class TokenUsageVO {
    private int totalRequests;
    private long totalPromptTokens;
    private long totalCompletionTokens;
    private long totalTokens;
    private long totalDurationMs;
}
