package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口16/17 置信度信号（正面/负面）入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbConfidenceSignalDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 目标对象类型（必填） */
    private String targetObjectType;

    /** 目标对象主键（必填） */
    private Long objectId;

    /** 幂等键（调用方生成，必填） */
    private String signalKey;

    /** 证据 */
    private String evidence;

    /** 负面信号：error>=2 时是否自动创建审核任务，默认 true */
    private Boolean autoCreateTask;
}
