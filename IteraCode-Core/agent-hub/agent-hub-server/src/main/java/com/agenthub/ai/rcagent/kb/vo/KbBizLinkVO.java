package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 业务链路 VO。
 */
@Data
public class KbBizLinkVO {

    private Long id;

    private String dsId;

    private String bizOperation;

    private String serviceClass;

    private String daoClass;

    private Long sqlTemplateId;

    /** JSON 数组文本 */
    private String relatedTables;

    private BigDecimal confidence;

    private Integer verified;

    private String evidence;

    private Integer version;

    private String status;

    private String knowledgeSource;
}
