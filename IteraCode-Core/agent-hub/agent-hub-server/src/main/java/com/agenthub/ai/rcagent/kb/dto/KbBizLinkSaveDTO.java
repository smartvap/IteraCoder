package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接口7 保存业务链路入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbBizLinkSaveDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 业务操作名（必填） */
    private String bizOperation;

    /** service 类 */
    private String serviceClass;

    /** dao 类 */
    private String daoClass;

    /** 关联 SQL 模板 id */
    private Long sqlTemplateId;

    /** 涉及表 */
    private List<String> relatedTables;

    /** 知识来源，默认 CODE */
    private String knowledgeSource;

    /** 置信度，默认 0.95 */
    private BigDecimal confidence;

    /** 已验证，默认 0 */
    private Integer verified;

    /** 证据 */
    private String evidence;

    /** 状态，默认 draft */
    private String status;
}
