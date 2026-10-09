package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接口4 保存业务概念入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbConceptSaveDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 概念名（必填） */
    private String conceptName;

    /** 定义（必填） */
    private String definition;

    /** 同义词 */
    private List<String> synonyms;

    /** 关联表 */
    private List<String> relatedTables;

    /** 关联字段 */
    private List<String> relatedColumns;

    /** 关联 SQL 模板 id */
    private List<Long> relatedSqlIds;

    /** 知识库 B 空间名 */
    private String spaceName;

    /** 知识来源，默认 LLM */
    private String knowledgeSource;

    /** 置信度，默认 0.6 */
    private BigDecimal confidence;

    /** 已验证，默认 0 */
    private Integer verified;

    /** 证据 */
    private String evidence;

    /** 状态，默认 draft */
    private String status;
}
