package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 指标定义 VO。
 */
@Data
public class KbMetricVO {

    private Long id;

    private String dsId;

    private String metricName;

    private String definition;

    private String calculationRule;

    /** JSON 数组文本 */
    private String relatedTables;

    /** JSON 数组文本 */
    private String relatedColumns;

    private String spaceName;

    private BigDecimal confidence;

    private Integer verified;

    private String evidence;

    private Integer version;

    private String status;

    private String knowledgeSource;
}
