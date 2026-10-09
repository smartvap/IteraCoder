package com.agenthub.ai.rcagent.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 索引字典（kb_index）：业务库索引级 Schema 事实知识。
 *
 * <p>字段风格与命名严格对齐 {@link KbTable}/{@link KbColumn}，以复用既有检索过滤口径
 * （{@code verified=1 AND status='active'}）与采集入库口径（SCHEMA 来源显式 active + verified=1）。
 * 幂等落库锚点为唯一键 {@code uk_ds_table_index (ds_id, table_id, index_name)}。</p>
 */
@Data
@TableName(value = "kb_index")
public class KbIndex implements KbKnowledgeRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源实例 ID（隔离维度，强制过滤） */
    private String dsId;

    /** 所属表 ID（kb_table.id，逻辑外键，不建物理外键） */
    private Long tableId;

    private String dbType;

    /** Schema/库名（冗余自 kb_table 便于检索） */
    private String schemaName;

    /** 表名（冗余自 kb_table 便于检索） */
    private String tableName;

    /** 索引名（MySQL 主键索引为 PRIMARY；Oracle 为索引名） */
    private String indexName;

    /** 索引列清单（JSON 数组文本，按索引内列顺序，与 IndexMeta.columns 同序） */
    private String indexColumns;

    /** 是否唯一索引 0/1 */
    private Integer isUnique;

    /** 是否主键索引 0/1 */
    private Integer isPrimary;

    /** 索引类型（BTREE/NORMAL/HASH/…，原样透传，不做业务枚举约束） */
    private String indexType;

    private String knowledgeSource;

    private BigDecimal confidence;

    private Integer verified;

    /** 证据（如 kb-schema-crawler;snapshot；禁止写入连接串/凭据） */
    private String evidence;

    /** 状态：draft/active/needs_review/deprecated */
    private String status;

    private Integer version;

    private Integer hitCount;

    private Integer errorCount;

    private Date createTime;

    private Date updateTime;
}
