package com.agenthub.ai.rcagent.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 表字典（kb_table）：业务库表级 Schema 事实知识。
 */
@Data
@TableName(value = "kb_table")
public class KbTable implements KbKnowledgeRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源实例 ID（BR-004 隔离维度） */
    private String dsId;

    /** 库类型：MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE */
    private String dbType;

    /** Schema/库名 */
    private String schemaName;

    /** 表名 */
    private String tableName;

    /** 表类型：TABLE/VIEW */
    private String tableType;

    /** 表注释 */
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
