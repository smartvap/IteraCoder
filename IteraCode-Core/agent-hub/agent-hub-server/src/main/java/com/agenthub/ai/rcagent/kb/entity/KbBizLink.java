package com.agenthub.ai.rcagent.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 业务链路（kb_biz_link）：业务操作 → service → dao → sql_template 的调用链路。
 */
@Data
@TableName(value = "kb_biz_link")
public class KbBizLink implements KbKnowledgeRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dsId;

    /** 业务操作名 */
    private String bizOperation;

    /** 入口 service 类全限定名 */
    private String serviceClass;

    /** dao/mapper 类全限定名 */
    private String daoClass;

    /** 关联 SQL 模板 id（kb_sql_template.id，逻辑外键） */
    private Long sqlTemplateId;

    /** 涉及表（JSON 数组，冗余便于按表反查） */
    private String relatedTables;

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
