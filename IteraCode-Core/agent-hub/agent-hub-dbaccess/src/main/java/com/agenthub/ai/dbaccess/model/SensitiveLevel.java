package com.agenthub.ai.dbaccess.model;

import java.util.Locale;

/**
 * 字段敏感等级（data-model.md SensitiveLevel）。
 */
public enum SensitiveLevel {

    /** 无敏感 */
    NONE("NONE", "无敏感"),

    /** 一般业务字段 */
    LOW("LOW", "低敏感"),

    /** 需谨慎（如客户名称） */
    MEDIUM("MEDIUM", "中敏感"),

    /** 强敏感（身份证/手机号/账号/金额等） */
    HIGH("HIGH", "高敏感");

    private final String code;
    private final String desc;

    SensitiveLevel(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /** 等级数值权重：NONE=0 … HIGH=3，便于策略比较 */
    public int weight() {
        return ordinal();
    }

    /**
     * 大小写不敏感解析，未知默认 {@link #NONE}。
     */
    public static SensitiveLevel parseOrDefault(String text) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        for (SensitiveLevel level : values()) {
            if (level.code.equals(upper) || level.name().equals(upper)) {
                return level;
            }
        }
        return NONE;
    }
}
