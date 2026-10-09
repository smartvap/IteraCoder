package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 业务概念 VO。
 */
@Data
public class KbConceptVO {

    private Long id;

    private String dsId;

    private String conceptName;

    private String definition;

    /** JSON 数组文本 */
    private String synonyms;

    /** JSON 数组文本 */
    private String relatedTables;

    /** JSON 数组文本 */
    private String relatedColumns;

    /** JSON 数组文本 */
    private String relatedSqlIds;

    private String spaceName;

    private BigDecimal confidence;

    private Integer verified;

    private String evidence;

    private Integer version;

    private String status;

    private String knowledgeSource;
}
