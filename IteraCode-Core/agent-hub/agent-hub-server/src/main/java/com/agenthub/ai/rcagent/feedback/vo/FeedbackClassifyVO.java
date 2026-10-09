package com.agenthub.ai.rcagent.feedback.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 人工判定修改类型结果 VO（接口6 data）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackClassifyVO {

    private Long flowId;

    /** 人工确认类型 */
    private String changeType;

    /** REVIEW_CREATED（若 buildSuggestion=true）或 SUGGESTING */
    private String analyzeStatus;

    /** 新建审核任务 id */
    private List<Long> reviewTaskIds;
}
