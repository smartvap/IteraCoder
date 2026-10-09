package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Schema 列候选 VO。
 */
@Data
public class KbColumnVO {

    private Long id;

    private String dsId;

    private Long tableId;

    private String schemaName;

    private String tableName;

    private String columnName;

    private String dataType;

    private Integer isPk;

    private Integer isFk;

    private String commentText;

    /** JSON 数组文本 */
    private String semanticTags;

    private BigDecimal confidence;

    private Integer verified;

    private String evidence;

    private Integer version;

    private String status;

    private String knowledgeSource;
}
