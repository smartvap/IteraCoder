package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口14 审核批准入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbReviewApproveDTO {

    /** 审核人账号（必填，BR-003 不可匿名） */
    private String reviewer;

    /** 审核意见（必填，可填"同意"） */
    private String reviewComment;

    /** 是否先应用 suggested_change.patch 再 approved，默认 false */
    private Boolean applySuggested;
}
