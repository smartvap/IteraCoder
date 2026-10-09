package com.agenthub.ai.rcagent.kb.constant;

/**
 * 审核任务类型（data-model 数据字典 ReviewTaskType）。
 */
public enum KbReviewTaskType {

    /** 表/字段结构变化待复核 */
    SCHEMA_CHANGE("SCHEMA_CHANGE"),
    /** SQL 模板转正建议 */
    TEMPLATE_PROMOTE("TEMPLATE_PROMOTE"),
    /** 反馈修正建议 */
    FEEDBACK_CORRECTION("FEEDBACK_CORRECTION"),
    /** 指标定义审核 */
    METRIC_DEFINITION("METRIC_DEFINITION"),
    /** 业务概念定义审核 */
    CONCEPT_DEFINITION("CONCEPT_DEFINITION"),
    /** 表关系建议 */
    RELATION_SUGGESTION("RELATION_SUGGESTION"),
    /** LLM 推断知识强制人工确认 */
    LLM_INFERENCE("LLM_INFERENCE"),
    /** 结构变更影响复核 */
    STRUCTURE_IMPACT("STRUCTURE_IMPACT");

    private final String value;

    KbReviewTaskType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (KbReviewTaskType t : values()) {
            if (t.value.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
