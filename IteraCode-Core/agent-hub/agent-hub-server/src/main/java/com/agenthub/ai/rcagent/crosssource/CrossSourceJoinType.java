package com.agenthub.ai.rcagent.crosssource;

import java.util.Locale;

/**
 * 跨源 join 类型（module-003 cross-source-join）。
 *
 * <p>本期仅支持 {@link #INNER} / {@link #LEFT} 两种等值关联语义：
 * INNER 仅保留两侧均匹配的行；LEFT 保留左源全部行，未匹配行右侧列补 {@code NULL}。</p>
 *
 * <p>纯枚举、无状态；{@link #fromText(String)} 大小写不敏感，非法输入返回 {@code null}
 * （由调用方决定降级，禁止抛异常）。</p>
 */
public enum CrossSourceJoinType {

    /** 内连接：仅保留两侧均匹配的行 */
    INNER,
    /** 左连接：保留左源全部行，未匹配行右侧列补 NULL */
    LEFT;

    /**
     * 文本归一化解析（大小写不敏感）。
     *
     * @param text 计划/请求中的 join 类型文本（可空）
     * @return 合法枚举；空白或非法值返回 {@code null}（不抛异常）
     */
    public static CrossSourceJoinType fromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        for (CrossSourceJoinType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    /** 对外展示形态（如 {@code INNER JOIN}），不含 dsId/库名等内部信息 */
    public String display() {
        return name() + " JOIN";
    }
}
