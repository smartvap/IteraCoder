package com.agenthub.ai.dbaccess.security;

import com.agenthub.ai.dbaccess.model.SensitiveLevel;

/**
 * 敏感值脱敏执行器（对齐 .aspirecode/AGENTS.md 第 5.x 节脱敏规则）。
 *
 * <p>规则：手机号保留前 3 后 4；证件保留首尾；邮箱保留首字符与域名；
 * HIGH 通用值（如账号/金额/密钥类）全掩码，禁止部分保留原文特征。</p>
 */
public final class SensitiveMasker {

    private static final String FULL_MASK = "******";

    private SensitiveMasker() {
    }

    /**
     * 按敏感等级脱敏。
     *
     * @param value 原值
     * @param level 敏感等级（NONE 原样返回）
     * @return 脱敏后的值
     */
    public static String mask(String value, SensitiveLevel level) {
        if (value == null || value.isEmpty() || level == null || level == SensitiveLevel.NONE) {
            return value;
        }
        String masked = maskByKnownPattern(value);
        if (masked != null) {
            return masked;
        }
        return switch (level) {
            case HIGH -> fullMask(value);
            case MEDIUM -> keepHeadTail(value, 1, 1);
            case LOW -> keepMiddle(value);
            default -> value;
        };
    }

    /**
     * 展示样本兜底脱敏（用于元数据采样 sampleValue）：不区分登记等级，
     * 值命中已知强敏感模式（手机号/证件/邮箱）即做部分脱敏；其余未知格式一律全掩码，
     * 禁止原文回填（对齐 data-model「sampleValue 禁止原文」与问题2修复要求）。
     */
    public static String maskSample(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String masked = maskByKnownPattern(value);
        return masked != null ? masked : FULL_MASK;
    }

    /**
     * 已知强敏感模式（手机号/证件/邮箱）走部分脱敏，其余返回 null 由等级策略继续。
     */
    private static String maskByKnownPattern(String value) {
        String trimmed = value.trim();
        if (trimmed.matches("1\\d{10}")) {
            // 手机号保留前 3 后 4
            return trimmed.substring(0, 3) + "****" + trimmed.substring(7);
        }
        if (trimmed.matches("\\d{15}") || trimmed.matches("\\d{17}[0-9Xx]")) {
            // 身份证保留首 6 尾 4（18 位）或首 4 尾 2（15 位）
            if (trimmed.length() == 18) {
                return trimmed.substring(0, 6) + "********" + trimmed.substring(14);
            }
            return trimmed.substring(0, 4) + "*******" + trimmed.substring(11);
        }
        int at = trimmed.indexOf('@');
        if (at > 0 && trimmed.indexOf('.', at) > at) {
            // 邮箱保留首字符与域名：a***@domain.com
            return trimmed.charAt(0) + "***" + trimmed.substring(at);
        }
        return null;
    }

    private static String fullMask(String value) {
        // 全掩码不暴露原文长度特征（P0 安全基线）
        return FULL_MASK;
    }

    /** 保留首尾各 keep 个字符，中间以 * 掩码 */
    private static String keepHeadTail(String value, int head, int tail) {
        if (value.length() <= head + tail) {
            return value.substring(0, 1) + "*".repeat(Math.max(0, value.length() - 1));
        }
        return value.substring(0, head) + "*".repeat(Math.max(1, value.length() - head - tail))
                + value.substring(value.length() - tail);
    }

    /** LOW 通用：保留首尾 1 位 */
    private static String keepMiddle(String value) {
        if (value.length() <= 2) {
            return value.substring(0, 1) + "*";
        }
        return value.substring(0, 1) + "*".repeat(value.length() - 2) + value.substring(value.length() - 1);
    }
}
