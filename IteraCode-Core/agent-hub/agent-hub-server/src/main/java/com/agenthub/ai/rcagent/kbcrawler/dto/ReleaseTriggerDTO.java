package com.agenthub.ai.rcagent.kbcrawler.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口7 发版触发变更检测入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ReleaseTriggerDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 发版标识（写入 evidence） */
    private String releaseTag;

    /** 触发方（ci/hook/manual），默认 hook */
    private String source;
}
