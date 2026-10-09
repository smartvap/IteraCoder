package com.agenthub.ai.rcagent.sqlguard.constant;

import java.util.Locale;

/**
 * 敏感字段处理策略（sql_guard_policy.sensitive_policy，data-model.md 数据字典 SensitivePolicy）。
 *
 * <p>与 db-access-common SensitivePolicy 取值一致，独立声明避免模块间强类型依赖；
 * 执行层决策以 db-access-common 为准，本枚举用于策略存储/展示。</p>
 */
public enum SensitivePolicy {

    /** 命中敏感字段 → 脱敏展示 */
    MASK("MASK"),
    /** 命中 HIGH 敏感字段 → 拒绝返回（默认） */
    DENY("DENY"),
    /** 按权限组返回 */
    ALLOW_BY_PERMISSION("ALLOW_BY_PERMISSION");

    private final String code;

    SensitivePolicy(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static SensitivePolicy parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        for (SensitivePolicy p : values()) {
            if (p.code.equals(upper) || p.name().equals(upper)) {
                return p;
            }
        }
        return null;
    }
}
