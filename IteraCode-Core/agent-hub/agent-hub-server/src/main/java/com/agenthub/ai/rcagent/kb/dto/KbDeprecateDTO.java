package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口9 软删除知识入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbDeprecateDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 目标对象类型（TABLE/COLUMN/...，必填） */
    private String targetObjectType;

    /** 对象主键（必填） */
    private Long objectId;

    /** 删除原因（写入 evidence） */
    private String reason;
}
