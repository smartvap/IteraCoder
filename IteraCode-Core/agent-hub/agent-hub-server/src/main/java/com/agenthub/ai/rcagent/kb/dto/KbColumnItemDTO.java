package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 级联/批量保存列条目（表保存 columns 子项 / Schema batch columns）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbColumnItemDTO {

    /** 字段名（必填） */
    private String columnName;

    /** 数据类型（必填） */
    private String dataType;

    /** 是否主键 0/1 */
    private Integer isPk;

    /** 是否外键 0/1 */
    private Integer isFk;

    /** 字段注释 */
    private String commentText;

    /** 语义标签 */
    private List<String> semanticTags;

    /** 采样统计 */
    private KbSampleStats sampleStats;

    /** 知识来源，默认 SCHEMA */
    private String knowledgeSource;

    /** 置信度 */
    private BigDecimal confidence;

    /** 已验证 0/1 */
    private Integer verified;

    /** 证据 */
    private String evidence;

    /** 状态 */
    private String status;
}
