package com.agenthub.ai.rcagent.feedback.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 提交反馈结果 VO（接口1 data）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackSubmitVO {

    /** fb_feedback_flow.id */
    private Long flowId;

    /** module-004 kb_feedback.id */
    private Long feedbackId;

    /** RECEIVED（随后异步变 ANALYZING…） */
    private String status;

    /** 是否命中已有处理流（true 表示重复反馈复用既有流） */
    private Boolean duplicate;
}
