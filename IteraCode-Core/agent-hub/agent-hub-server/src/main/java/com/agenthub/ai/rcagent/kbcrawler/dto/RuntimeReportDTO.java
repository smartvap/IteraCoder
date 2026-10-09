package com.agenthub.ai.rcagent.kbcrawler.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * 接口6 运行时 SQL 批量上报入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RuntimeReportDTO {

    /** 数据源实例 ID（接入方配置，必填） */
    private String dsId;

    /** 证据来源：runtime-interceptor/oracle-v$sql/mysql-perf-schema/apm */
    private String source;

    /** SQL 执行事件批量（必填） */
    private List<RuntimeSqlEventDTO> events;
}
