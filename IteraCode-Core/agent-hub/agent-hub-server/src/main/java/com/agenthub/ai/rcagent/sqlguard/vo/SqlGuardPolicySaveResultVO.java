package com.agenthub.ai.rcagent.sqlguard.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 保存安全策略响应（HTTP 接口 2 data）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SqlGuardPolicySaveResultVO {

    /** sql_guard_policy.id */
    private Long id;

    /** 更新后版本 */
    private Integer configVersion;

    /** 热加载结果：RELOADED / CACHE_BACKED */
    private String reload;
}
