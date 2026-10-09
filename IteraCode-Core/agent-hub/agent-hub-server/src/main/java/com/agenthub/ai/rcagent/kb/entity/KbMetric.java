package com.agenthub.ai.rcagent.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 指标定义（kb_metric）：统一指标口径（如"本月投诉量"）。
 */
@Data
@TableName(value = "kb_metric")
public class KbMetric implements KbKnowledgeRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dsId;

    /** 指标名 */
    private String metricName;

    /** 指标定义 */
    private String definition;

    /** 计算规则/口径（SQL 模板引用或文本规则） */
    private String calculationRule;

    /** 关联表（JSON 数组） */
    private String relatedTables;

    /** 关联字段（JSON 数组） */
    private String relatedColumns;

    /** 知识库 B 知识空间名 */
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
