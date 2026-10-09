package com.agenthub.ai.rcagent.sqlguard.constant;

import java.util.Locale;

/**
 * SELECT * 策略（sql_guard_policy.select_star_policy，data-model.md 数据字典 SelectStarPolicy）。
 */
public enum SelectStarPolicy {

    /** SELECT * / t.* 一律 BLOCK（默认） */
    BLOCK("BLOCK"),
    /** 允许 SELECT *（需人工开启，db-access-common allowSelectStar 亦需配套） */
    ALLOW("ALLOW");

    private final String code;

    SelectStarPolicy(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static SelectStarPolicy parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        for (SelectStarPolicy p : values()) {
            if (p.code.equals(upper) || p.name().equals(upper)) {
                return p;
            }
        }
        return null;
    }
}
