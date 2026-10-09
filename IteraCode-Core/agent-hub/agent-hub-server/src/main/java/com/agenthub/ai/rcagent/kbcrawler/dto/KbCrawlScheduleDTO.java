package com.agenthub.ai.rcagent.kbcrawler.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 修改采集调度配置入参（接口2）。
 *
 * <p>JSON 键按 snake_case：{@code ds_id} / {@code schedule_cron}。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbCrawlScheduleDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** Spring 6 段 cron 表达式；空串/null = 清除自定义回落默认 */
    private String scheduleCron;
}
