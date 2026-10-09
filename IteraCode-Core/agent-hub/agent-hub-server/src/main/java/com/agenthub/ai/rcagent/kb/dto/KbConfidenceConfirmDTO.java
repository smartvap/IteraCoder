package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口18 置信度人工确认入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbConfidenceConfirmDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 目标对象类型（必填） */
    private String targetObjectType;

    /** 目标对象主键（必填） */
    private Long objectId;

    /** 确认人（必填） */
    private String reviewer;

    /** 证据 */
    private String evidence;
}
