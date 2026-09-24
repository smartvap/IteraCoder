package com.agenthub.ai.dbaccess.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 中立关系元数据（data-model.md t_meta_relation，不落库）。
 *
 * <p>仅记录元数据，不承载具体业务行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationMeta {

    /** 源表 */
    private String sourceTable;

    /** 源字段 */
    private String sourceColumn;

    /** 目标表 */
    private String targetTable;

    /** 目标字段 */
    private String targetColumn;

    /** 关系类型（FK/REFERENCE/...） */
    private String relationType;

    /** 约束/索引名（可空） */
    private String constraintName;
}
