package com.agenthub.ai.rcagent.feedback.constant;

/**
 * 修改类型（fb_feedback_flow.change_type，同时写 kb_feedback.change_type）。
 *
 * <p>含 module-004 kb ChangeType 的 7 类 + 归因反馈扩展（ATTRIBUTION_*）+ 概念补充，
 * 与 data-model 数据字典 ChangeType 一致。归因扩展字段在 module-004 校验
 * （kb.constant.ChangeType）可能不被接受，因此写 kb_feedback.change_type 时仅
 * 同步 module-004 认可的值，未认可值以 fb_feedback_flow.change_type 为准。</p>
 */
public enum ChangeType {

    /** SQL 错误（语法/写法） */
    SQL_ERROR("SQL_ERROR"),
    /** 字段错误（选错列/列名） */
    FIELD_ERROR("FIELD_ERROR"),
    /** 语义错误（条件含义理解偏差） */
    SEMANTIC_ERROR("SEMANTIC_ERROR"),
    /** JOIN 错误（关联关系/字段错误） */
    JOIN_ERROR("JOIN_ERROR"),
    /** 时间范围错误 */
    TIME_RANGE_ERROR("TIME_RANGE_ERROR"),
    /** 聚合/口径错误（分组、聚合函数、指标口径） */
    AGG_ERROR("AGG_ERROR"),
    /** 归因反馈：维度/对象范围修正 */
    ATTRIBUTION_SCOPE("ATTRIBUTION_SCOPE"),
    /** 归因反馈：根因结论修正 */
    ATTRIBUTION_CAUSE("ATTRIBUTION_CAUSE"),
    /** 归因反馈：指标口径修正 */
    ATTRIBUTION_CALIBER("ATTRIBUTION_CALIBER"),
    /** 概念/FAQ/解释补充 */
    CONCEPT_SUPPLEMENT("CONCEPT_SUPPLEMENT"),
    /** 其他 */
    OTHER("OTHER");

    private final String value;

    ChangeType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (ChangeType t : values()) {
            if (t.value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    public static ChangeType from(String v) {
        if (v == null) {
            return null;
        }
        for (ChangeType t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        return null;
    }
}
