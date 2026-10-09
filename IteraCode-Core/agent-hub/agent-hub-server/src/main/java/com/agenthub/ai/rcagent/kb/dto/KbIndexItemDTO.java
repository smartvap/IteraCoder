package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 级联/批量保存索引条目（表保存 indexes 子项 / Schema batch indexes）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbIndexItemDTO {

    /** 索引名（必填） */
    private String indexName;

    /** 索引列清单（有序，与 IndexMeta.columns 同序） */
    private List<String> columns;

    /** 是否唯一索引 0/1 */
    private Integer isUnique;

    /** 是否主键索引 0/1 */
    private Integer isPrimary;

    /** 索引类型（原样透传） */
    private String indexType;

    /** 知识来源，默认 SCHEMA */
    private String knowledgeSource;

    /** 置信度 */
    private BigDecimal confidence;

    /** 已验证 0/1 */
    private Integer verified;

    /** 状态 */
    private String status;

    /** 证据（禁含凭据） */
    private String evidence;
}
