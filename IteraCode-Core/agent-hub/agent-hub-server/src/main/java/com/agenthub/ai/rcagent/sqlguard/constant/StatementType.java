package com.agenthub.ai.rcagent.sqlguard.constant;

import java.util.Locale;

/**
 * 语句类型分类（Parser 输出归并，data-model.md 数据字典 StatementType）。
 */
public enum StatementType {

    /** 只读查询（含 EXPLAIN） */
    SELECT("SELECT", "只读查询"),
    /** INSERT/UPDATE/DELETE/MERGE（写形态，BLOCK） */
    DML("DML", "写语句"),
    /** CREATE/ALTER/DROP/TRUNCATE 等（BLOCK） */
    DDL("DDL", "DDL"),
    /** START/BEGIN/COMMIT/ROLLBACK 等 */
    TRANSACTION("TRANSACTION", "事务语句"),
    /** 多语句/存储过程含写（BLOCK） */
    MULTI("MULTI", "多语句"),
    /** Parser 无法识别方言/语法（走 L1 Syntax FAIL） */
    UNKNOWN("UNKNOWN", "无法识别");

    private final String code;
    private final String desc;

    StatementType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static StatementType parse(String text) {
        if (text == null || text.isBlank()) {
            return UNKNOWN;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        for (StatementType t : values()) {
            if (t.code.equals(upper) || t.name().equals(upper)) {
                return t;
            }
        }
        return UNKNOWN;
    }
}
