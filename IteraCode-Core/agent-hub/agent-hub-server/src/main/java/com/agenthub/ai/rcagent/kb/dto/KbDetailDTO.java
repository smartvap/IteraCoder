package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口10 查询知识详情入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbDetailDTO {

    /** 目标对象类型（必填） */
    private String targetObjectType;

    /** 对象主键（必填） */
    private Long objectId;
}
