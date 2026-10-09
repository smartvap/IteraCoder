package com.agenthub.ai.rcagent.sqlguard.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SQL 审计文本工具：归一化抽象（占位符化）+ SHA-256 指纹。
 *
 * <p>安全约束（BR-003/AGENTS.md）：审计字段只存抽象摘要与指纹，<b>禁止落 SQL 原文与敏感字面量</b>；
 * 归一化时把字符串/数字字面量替换为 {@code ?}，保留表名/列名结构。</p>
 */
public final class SqlTextUtil {

    /** 字符串字面量（单引号段） */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    /** 数字字面量（含正负/小数） */
    private static final Pattern NUMBER_LITERAL = Pattern.compile("\\b-?\\d+(?:\\.\\d+)?\\b");
    /** 空白折叠 */
    private static final Pattern WS = Pattern.compile("\\s+");

    private SqlTextUtil() {
    }

    /**
     * 归一化 + 占位符化（无字面量、压缩空白、关键字统一大写便于审计可读）。
     */
    public static String abstractSql(String sql) {
        if (sql == null) {
            return "";
        }
        String normalized = STRING_LITERAL.matcher(sql).replaceAll("'?'");
        normalized = NUMBER_LITERAL.matcher(normalized).replaceAll("?");
        normalized = WS.matcher(normalized).replaceAll(" ").trim();
        return truncate(normalized, 4000);
    }

    /**
     * SHA-256 HEX 指纹（对归一化抽象计算，幂等检索）。
     */
    public static String fingerprint(String sql) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(abstractSql(sql).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 日志/摘要长文本截断（对齐 ConversationLogger 截断思路）。
     */
    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
