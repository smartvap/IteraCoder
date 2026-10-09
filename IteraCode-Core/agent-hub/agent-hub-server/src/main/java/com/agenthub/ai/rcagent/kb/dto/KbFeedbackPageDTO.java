package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 接口26 反馈分页查询入参。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbFeedbackPageDTO extends KbPageDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** PENDING/ANALYZING/... */
    private String status;

    /** 按会话追溯 */
    private String sessionId;

    /** 创建开始时间 */
    private String startTime;

    /** 创建结束时间 */
    private String endTime;
}
