package com.agenthub.ai.rcagent.engine.dto;

import lombok.Data;

/**
 * 结果反馈入参（POST /api/v1/rcagent/feedback）。
 */
@Data
public class RcaFeedbackRequest {

    /** 会话线程 ID */
    private String threadId;

    /** 反馈类型：USEFUL / USELESS / CORRECTION */
    private String feedbackType;

    /** 反馈内容/纠错文本（≤2000，日志截断） */
    private String comment;
}
