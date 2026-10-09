package com.agenthub.ai.rcagent.kbcrawler.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口4/5 暂停/恢复采集任务入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class CrawlPauseResumeDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;
}
