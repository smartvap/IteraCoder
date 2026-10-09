package com.agenthub.ai.rcagent.kbcrawler.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口1 手动触发采集任务入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CrawlTriggerDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 采集类型：SCHEMA_FULL/SCHEMA_INCR/MAPPER_PARSE/RUNTIME_COLLECT/CHANGE_DETECT（必填） */
    private String taskType;

    /** Schema 任务 FULL/INCR（默认 INCR；仅 SCHEMA 类型生效） */
    private String mode;

    /** 触发来源：MANUAL/SCHEDULE/RELEASE（默认 MANUAL） */
    private String triggerType;
}
