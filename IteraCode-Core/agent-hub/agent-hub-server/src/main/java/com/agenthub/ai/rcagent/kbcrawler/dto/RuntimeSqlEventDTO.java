package com.agenthub.ai.rcagent.kbcrawler.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口6 运行时 SQL 上报单条事件（上报事件禁携带参数明细/敏感值）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RuntimeSqlEventDTO {

    /** 真实执行 SQL（本地仅内存流转，日志截断） */
    private String sql;

    /** 参数摘要（类型+个数，如 "[String:1,Long:2]"，禁值） */
    private String paramBrief;

    /** 耗时 ms */
    private Long costMs;

    /** 链路上下文 JSON（service/dao 方法、thread） */
    private Object bizContext;

    /** 执行时间 ISO */
    private String occurredAt;
}
