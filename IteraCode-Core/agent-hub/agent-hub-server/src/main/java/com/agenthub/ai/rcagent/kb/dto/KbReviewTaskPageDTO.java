package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 接口12 审核任务分页入参。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbReviewTaskPageDTO extends KbPageDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** pending/approved/rejected，默认全部（不含过滤） */
    private String status;

    /** 任务类型 */
    private String taskType;

    /** 目标类型 */
    private String targetObjectType;

    /** 审核人 */
    private String reviewer;

    /** 创建开始时间 yyyy-MM-dd HH:mm:ss */
    private String startTime;

    /** 创建结束时间 yyyy-MM-dd HH:mm:ss */
    private String endTime;
}
