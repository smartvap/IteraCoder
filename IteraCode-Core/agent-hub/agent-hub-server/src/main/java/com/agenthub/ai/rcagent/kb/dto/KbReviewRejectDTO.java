package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口15 审核拒绝入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbReviewRejectDTO {

    /** 审核人账号（必填） */
    private String reviewer;

    /** 拒绝意见（必填） */
    private String reviewComment;

    /** 是否同时置目标 deprecated，默认 false */
    private Boolean deprecateTarget;

    /** 是否以当前建议重开为新任务，默认 false */
    private Boolean reopenSuggested;
}
