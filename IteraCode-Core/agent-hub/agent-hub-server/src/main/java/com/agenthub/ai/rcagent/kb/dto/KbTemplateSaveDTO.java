package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 接口6 保存 SQL 模板入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbTemplateSaveDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 库类型 */
    private String dbType;

    /** 业务动作 */
    private String bizAction;

    /** 归一化 SQL（必填） */
    private String normalizedSql;

    /** 涉及表 */
    private List<String> involvedTables;

    /** 样本问法 */
    private List<String> sampleQuestions;

    /** 命中频次（默认 1） */
    private Integer freq;

    /** 准确率 */
    private BigDecimal accuracy;

    /** 知识来源，默认 RUNTIME */
    private String knowledgeSource;

    /** 置信度，默认 0.8 */
    private BigDecimal confidence;

    /** 已验证，默认 0 */
    private Integer verified;

    /** 证据 */
    private String evidence;

    /** 状态，默认 draft */
    private String status;
}
