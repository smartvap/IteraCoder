package com.agenthub.ai.rcagent.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 业务概念（kb_business_concept）：统一业务概念口径（如"客户""投诉量"）。
 */
@Data
@TableName(value = "kb_business_concept")
public class KbBusinessConcept implements KbKnowledgeRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dsId;

    /** 概念名（统一术语） */
    private String conceptName;

    /** 概念定义/口径说明 */
    private String definition;

    /** 同义词（JSON 数组，检索匹配） */
    private String synonyms;

    /** 关联表（JSON 数组，如 ["CUSTOMER"]） */
    private String relatedTables;

    /** 关联字段（JSON 数组，如 ["CUSTOMER.BAL_AMT"]） */
    private String relatedColumns;

    /** 关联 SQL 模板 id（JSON 数组） */
    private String relatedSqlIds;

    /** 知识库 B 知识空间名（llm-wiki 映射） */
    private String spaceName;

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
