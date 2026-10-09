package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Schema 表候选 VO。
 */
@Data
public class KbTableVO {

    private Long id;

    private String dsId;

    private String dbType;

    private String schemaName;

    private String tableName;

    private String tableType;

    private String commentText;

    /** JSON 数组文本 */
    private String semanticTags;

    private BigDecimal confidence;

    private Integer verified;

    private String evidence;

    private Integer version;

    private String status;

    /** 采样统计 JSON（脱敏） */
    private String sampleStats;

    /** 来源 */
    private String knowledgeSource;
}
