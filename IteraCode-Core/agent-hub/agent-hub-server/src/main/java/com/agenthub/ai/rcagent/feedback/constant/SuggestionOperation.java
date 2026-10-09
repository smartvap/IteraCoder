package com.agenthub.ai.rcagent.feedback.constant;

/**
 * 建议操作类型（suggestions JSON 数组元素 operation）。
 */
public enum SuggestionOperation {

    /** 新建对象/新知识采集 */
    NEW("NEW"),
    /** 更新已有对象 */
    UPDATE("UPDATE"),
    /** 废弃/软删除 */
    DEPRECATE("DEPRECATE"),
    /** 转正（模板提升为正式知识） */
    PROMOTE("PROMOTE");

    private final String value;

    SuggestionOperation(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (SuggestionOperation o : values()) {
            if (o.value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    public static SuggestionOperation from(String v) {
        if (v == null) {
            return null;
        }
        for (SuggestionOperation o : values()) {
            if (o.value.equals(v)) {
                return o;
            }
        }
        return null;
    }
}
