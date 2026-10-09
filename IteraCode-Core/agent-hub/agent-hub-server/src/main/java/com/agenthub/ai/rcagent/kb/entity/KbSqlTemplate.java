package com.agenthub.ai.rcagent.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * SQL 模板（kb_sql_template）：可复用 SQL 模板（归一化 SQL/涉及表/样本问法）。
 */
@Data
@TableName(value = "kb_sql_template")
public class KbSqlTemplate implements KbKnowledgeRow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dsId;

    private String dbType;

    /** 业务动作（如 查余额/统计投诉量） */
    private String bizAction;

    /** 归一化 SQL（占位符归一、无敏感参数） */
    private String normalizedSql;

    /** normalized_sql 指纹（SHA-256 前 64 位 hex） */
    private String sqlFingerprint;

    /** 涉及表（JSON 数组，结构变更/影响分析用） */
    private String involvedTables;

    /** 样本问法（JSON 数组，检索匹配） */
    private String sampleQuestions;

    /** 命中频次（运行时采集聚合） */
    private Integer freq;

    /** 准确率（历史命中正确率） */
    private BigDecimal accuracy;

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
