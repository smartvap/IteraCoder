package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接口2 保存字段知识入参（table_id 与 schema_name+table_name 二选一）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbColumnSaveDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 父表 id（与 schema_name+table_name 二选一） */
    private Long tableId;

    /** Schema 名（与 table_id 二选一） */
    private String schemaName;

    /** 表名（与 table_id 二选一） */
    private String tableName;

    /** 字段名（必填） */
    private String columnName;

    /** 数据类型（必填） */
    private String dataType;

    /** 是否主键 0/1 */
    private Integer isPk;

    /** 是否外键 0/1 */
    private Integer isFk;

    /** 字段注释 */
    private String commentText;

    /** 语义标签数组 */
    private List<String> semanticTags;

    /** 采样统计 */
    private KbSampleStats sampleStats;

    /** 知识来源，默认 SCHEMA */
    private String knowledgeSource;

    /** 置信度，按来源默认 */
    private BigDecimal confidence;

    /** 已验证 0/1 */
    private Integer verified;

    /** 证据 */
    private String evidence;

    /** 状态，默认 draft */
    private String status;
}
