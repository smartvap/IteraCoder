package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 表关系 VO。
 */
@Data
public class KbRelationVO {

    private Long id;

    private String dsId;

    private String dbType;

    private String tableA;

    private String columnA;

    private String tableB;

    private String columnB;

    /** JSON 数组文本 */
    private String joinFields;

    private String relationType;

    private String relationSource;

    private BigDecimal confidence;

    private Integer verified;

    private String evidence;

    private Integer version;

    private String status;

    private String knowledgeSource;
}
