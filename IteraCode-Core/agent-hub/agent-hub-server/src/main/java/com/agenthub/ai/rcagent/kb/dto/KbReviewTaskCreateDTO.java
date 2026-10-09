package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口11 创建审核任务入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbReviewTaskCreateDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 任务类型（必填） */
    private String taskType;

    /** 目标对象类型（必填） */
    private String targetObjectType;

    /** 目标对象标识 JSON（{objectId, desc}，必填） */
    private String targetObject;

    /** 结构化建议 JSON（{operation, patch}） */
    private String suggestedChange;

    /** 证据 */
    private String evidence;
}
