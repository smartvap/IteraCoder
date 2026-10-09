package com.agenthub.ai.rcagent.sqlguard.constant;

import java.util.Locale;

/**
 * 处置结论（data-model.md 数据字典 Verdict）。
 */
public enum SqlGuardVerdict {

    /** LOW 自动放行只读执行 */
    ALLOW("ALLOW", "自动放行"),
    /** MED/HIGH 转人工审核 */
    REVIEW("REVIEW", "人工审核"),
    /** 直接禁止 */
    BLOCK("BLOCK", "禁止");

    private final String code;
    private final String desc;

    SqlGuardVerdict(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static SqlGuardVerdict parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        for (SqlGuardVerdict v : values()) {
            if (v.code.equals(upper) || v.name().equals(upper)) {
                return v;
            }
        }
        return null;
    }
}
