package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

/**
 * 反馈落库响应 VO（接口25）。
 */
@Data
public class KbFeedbackSaveVO {

    private Long feedbackId;

    private String status;
}
