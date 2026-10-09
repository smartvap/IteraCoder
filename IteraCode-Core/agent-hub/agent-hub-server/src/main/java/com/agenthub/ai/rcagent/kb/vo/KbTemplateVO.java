package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * SQL 模板 VO。
 */
@Data
public class KbTemplateVO {

    private Long id;

    private String dsId;

    private String dbType;

    private String bizAction;

    /** 归一化 SQL（脱敏后） */
    private String normalizedSql;

    private String sqlFingerprint;

    /** JSON 数组文本 */
    private String involvedTables;

    /** JSON 数组文本 */
    private String sampleQuestions;

    private Integer freq;

    private BigDecimal accuracy;

    private BigDecimal confidence;

    private Integer verified;

    private String evidence;

    private Integer version;

    private String status;

    private String knowledgeSource;
}
