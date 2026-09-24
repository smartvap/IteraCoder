package com.agenthub.ai.dbaccess.meta;

import com.agenthub.ai.dbaccess.model.SensitiveLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 中立列元数据（data-model.md t_meta_column，不落库）。
 *
 * <p>{@code sensitiveLevel} 供敏感字段策略（脱敏/拒绝）判定；{@code sampleValue} 必须为已脱敏展示样本。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnMeta {

    /** 所属表名 */
    private String tableName;

    /** 字段名 */
    private String columnName;

    /** 字段序号 */
    private Integer ordinal;

    /** JDBC 类型名（如 VARCHAR/INTEGER/DATE） */
    private String dataType;

    /** 长度/精度（既有语义不变：字符族取字符最大长度 / Oracle CHAR_LENGTH 优先 / Fallback COLUMN_SIZE） */
    private Integer columnSize;

    /** 数值型精度（MySQL NUMERIC_PRECISION / Oracle DATA_PRECISION / Fallback COLUMN_SIZE；非数值型为 NULL） */
    private Integer numericPrecision;

    /** 数值型标度（MySQL NUMERIC_SCALE / Oracle DATA_SCALE / Fallback DECIMAL_DIGITS；非数值型为 NULL） */
    private Integer numericScale;

    /** 是否可空（0=否 1=是） */
    private Integer nullable;

    /** 是否主键（0=否 1=是） */
    private Integer isPrimaryKey;

    /** 是否外键（0=否 1=是） */
    private Integer isForeignKey;

    /** 字段注释 */
    private String columnComment;

    /** 敏感等级（默认 NONE），来自敏感清单登记 */
    @Builder.Default
    private SensitiveLevel sensitiveLevel = SensitiveLevel.NONE;

    /** 采样值（按 AGENTS.md 脱敏后的展示样本，可空，禁止原文） */
    private String sampleValue;
}
