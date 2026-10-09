package com.agenthub.ai.rcagent.sqlguard.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Schema 字典列命中（kb 列候选/运行时元数据列）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaColumnHit {

    /** 表所属库/Schema（kb schemaName，可空；运行时元数据未提供时为 null） */
    private String schema;

    /** 表名（纯表名，不含 schema） */
    private String table;

    /** 列名 */
    private String column;

    /** 数据类型（JDBC 名，可空） */
    private String dataType;

    /** 是否主键 */
    private Boolean pk;

    /** 是否外键 */
    private Boolean fk;

    /** 来源 */
    private String source;
}
