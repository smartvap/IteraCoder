package com.agenthub.ai.rcagent.sqlguard.constant;

import java.util.Locale;

/**
 * 策略作用域（sql_guard_policy.policy_scope）。
 */
public enum PolicyScope {

    /** 全局默认策略（ds_id 空） */
    GLOBAL("GLOBAL"),
    /** 数据源覆盖策略（ds_id 必填） */
    DS("DS");

    private final String code;

    PolicyScope(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static PolicyScope parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        for (PolicyScope s : values()) {
            if (s.code.equals(upper) || s.name().equals(upper)) {
                return s;
            }
        }
        return null;
    }
}
