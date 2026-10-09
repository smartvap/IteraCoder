package com.agenthub.ai.rcagent.kb.constant;

/**
 * 库类型（data-model 数据字典 DbType）。
 */
public enum KbDbType {

    MYSQL("MYSQL"),
    ORACLE("ORACLE"),
    OCEANBASE_MYSQL("OCEANBASE_MYSQL"),
    OCEANBASE_ORACLE("OCEANBASE_ORACLE");

    private final String value;

    KbDbType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (KbDbType t : values()) {
            if (t.value.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
