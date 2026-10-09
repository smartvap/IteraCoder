package com.agenthub.ai.rcagent.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 字段字典（kb_column）：业务库列级 Schema 事实知识。
 */
@Data
@TableName(value = "kb_column")
public class KbColumn implements KbKnowledgeRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dsId;

    /** 所属表 ID（kb_table.id，逻辑外键） */
    private Long tableId;

    private String dbType;

    /** Schema 名（冗余自 kb_table 便于检索） */
    private String schemaName;

    /** 表名（冗余自 kb_table 便于检索） */
    private String tableName;

    /** 字段名 */
    private String columnName;

    /** 数据类型（JDBC 类型/长度，如 VARCHAR(32)） */
    private String dataType;

    /** 是否主键 0/1 */
    private Integer isPk;

    /** 是否外键 0/1 */
    private Integer isFk;

    /** 字段注释 */
    private String commentText;

    /** 语义标签（JSON 数组） */
    private String semanticTags;

    /** 采样统计（JSON，脱敏） */
    private String sampleStats;

    private String knowledgeSource;

    private BigDecimal confidence;

    private Integer verified;

    private String evidence;

    /** 状态：draft/active/needs_review/deprecated */
    private String status;

    private Integer version;

    private Integer hitCount;

    private Integer errorCount;

    private Date createTime;

    private Date updateTime;
}
