package com.agenthub.ai.dbaccess.model;

import java.util.Locale;

/**
 * 敏感字段策略（data-model.md SensitivePolicy）。
 *
 * <ul>
 *   <li>MASK：命中敏感字段 → 脱敏后返回（如手机号/身份证部分掩码）</li>
 *   <li>DENY：命中敏感字段 → 拒绝执行（默认，安全基线）</li>
 *   <li>ALLOW_BY_PERMISSION：允许但需更高权限上下文（上层 module-006 判定）</li>
 * </ul>
 */
public enum SensitivePolicy {

    MASK("MASK", "脱敏后返回"),
    DENY("DENY", "拒绝执行"),
    ALLOW_BY_PERMISSION("ALLOW_BY_PERMISSION", "按权限放行");

    private final String code;
    private final String desc;

    SensitivePolicy(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 大小写不敏感解析，未知返回 {@code null}。
     */
    public static SensitivePolicy parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        for (SensitivePolicy policy : values()) {
            if (policy.code.equals(upper) || policy.name().equals(upper)) {
                return policy;
            }
        }
        return null;
    }
}
