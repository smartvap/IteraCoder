package com.agenthub.ai.rcagent.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 表关系（kb_relation）：表间关联（JOIN 字段/类型/来源/证据）。
 */
@Data
@TableName(value = "kb_relation")
public class KbRelation implements KbKnowledgeRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dsId;

    private String dbType;

    /** 关系表 A */
    private String tableA;

    /** 关系字段 A */
    private String columnA;

    /** 关系表 B */
    private String tableB;

    /** 关系字段 B */
    private String columnB;

    /** join 字段映射（JSON 数组，如 [["a.cust_id","b.id"]]） */
    private String joinFields;

    /** 关系类型：FK/REFERENCE */
    private String relationType;

    /** 关系证据来源：FK/CODE/RUNTIME_JOIN */
    private String relationSource;

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
