package com.agenthub.ai.rcagent.feedback.util;

import java.util.ArrayList;
import java.util.List;

/**
 * feedback 模块文本截断/脱敏工具（AGENTS.md 5.3/5.4）。
 *
 * <p>日志/异常/响应中的反馈文本、SQL、evidence 一律截断；P0 全量脱敏字段（Token/密码）
 * 禁止出现在新代码日志。方法均为纯逻辑。</p>
 */
public final class FeedbackSafeText {

    /** 默认截断长度（对齐 ConversationLogger.truncate 200 字符思路） */
    private static final int DEFAULT_LIMIT = 200;

    private FeedbackSafeText() {
    }

    /** 长文本截断至 limit，超出加省略号；null 返回空串 */
    public static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, limit) + "...";
    }

    /** 长文本截断至默认 200 字符 */
    public static String truncate(String text) {
        return truncate(text, DEFAULT_LIMIT);
    }

    /** 截断集合中每个元素 */
    public static List<String> truncateEach(List<String> items, int limit) {
        if (items == null) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>(items.size());
        for (String item : items) {
            result.add(truncate(item, limit));
        }
        return result;
    }

    /** 掩码敏感键值（token/key/password），值全量掩码为 ****** */
    public static String maskIfSensitive(String key, String value) {
        if (key == null) {
            return value;
        }
        String k = key.toLowerCase();
        if (k.contains("token") || k.contains("key") || k.contains("password")
                || k.contains("secret") || k.contains("authorization")) {
            return "******";
        }
        return value;
    }
}
