package com.agenthub.ai.rcagent.sqlguard.constant;

/**
 * 形态分类（SqlFeature.statementClass）。
 */
public enum StatementClass {

    /** 只读查询（SELECT/EXPLAIN） */
    READ_ONLY("READ_ONLY"),
    /** 写语句（INSERT/UPDATE/DELETE/MERGE） */
    WRITE("WRITE"),
    /** DDL */
    DDL("DDL"),
    /** 事务语句 */
    TRANSACTION("TRANSACTION"),
    /** 多语句 */
    MULTI("MULTI"),
    /** 非 SELECT 且无法归类 */
    NON_SELECT("NON_SELECT"),

    /** Parser 无法识别方言/语法（解析失败标记） */
    UNKNOWN("UNKNOWN");

    private final String code;

    StatementClass(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
