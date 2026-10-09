package com.agenthub.ai.rcagent.retrieval.constant;

/**
 * 检索要素类型（query_elements 意图要素，数据字典 RetrievalElementType）。
 */
public enum RetrievalElementType {

    /** 时间范围 */
    TIME_RANGE("TIME_RANGE"),
    /** 维度（如营业厅/时段/类型） */
    DIMENSION("DIMENSION"),
    /** 指标词（如投诉量） */
    METRIC("METRIC"),
    /** 实体词（如华东区/CRM） */
    ENTITY("ENTITY"),
    /** 业务概念候选（如投诉） */
    CONCEPT("CONCEPT"),
    /** 自由关键词 */
    KEYWORD("KEYWORD");

    private final String value;

    RetrievalElementType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
