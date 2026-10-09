package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SPI S-4 schemaCheck 入参（api-contract SqlSchemaCheckRequest）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlSchemaCheckRequest {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 只读 SQL（必填） */
    private String sql;

    /** 库类型（缺省从 ds_id 解析） */
    private String dbType;

    /** 是否校验 JOIN 关系（默认 true） */
    private Boolean includeRelations;

    public boolean includeRelations() {
        return !Boolean.FALSE.equals(includeRelations);
    }
}
