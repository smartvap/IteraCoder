package com.agenthub.ai.dbaccess.meta;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 中立索引元数据（data-model.md t_meta_index（逻辑模型，不落库），Provider 采集产物，不落库）。
 *
 * <p>所有字段与具体库类型无关，方言差异在 Provider 层吸收；仅作 Provider → 采集编排的数据载体。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexMeta {

    /** Schema/库名（MySQL 库名 / Oracle OWNER，与 TableMeta.spaceName 同口径） */
    private String spaceName;

    /** 表名 */
    private String tableName;

    /** 索引名（MySQL 主键索引为 PRIMARY） */
    private String indexName;

    /** 有序列清单（MySQL 按 SEQ_IN_INDEX 升序 / Oracle 按 COLUMN_POSITION 升序 / JDBC 按 ORDINAL_POSITION 升序） */
    private List<String> columns;

    /** 是否唯一（0=否 1=是） */
    private Integer isUnique;

    /** 是否主键索引（0=否 1=是） */
    private Integer isPrimary;

    /** 索引类型（可空，原样取自数据字典，不做业务枚举约束） */
    private String indexType;
}
