package com.agenthub.ai.rcagent.kb.constant;

/**
 * 修改类型（data-model 数据字典 ChangeType，feedback-loop 写入，本模块仅存储）。
 */
public enum ChangeType {

    SQL_ERROR("SQL_ERROR"),
    FIELD_ERROR("FIELD_ERROR"),
    SEMANTIC_ERROR("SEMANTIC_ERROR"),
    JOIN_ERROR("JOIN_ERROR"),
    TIME_RANGE_ERROR("TIME_RANGE_ERROR"),
    AGG_ERROR("AGG_ERROR"),
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
}
