package com.agenthub.ai.rcagent.sqlguard.constant;

import java.util.Locale;

/**
 * SQL 风险等级（v0.4 8.4）。
 *
 * <p>判定特征与处置见 data-model.md 数据字典 RiskLevel：BLOCK 直接禁止、LOW 自动只读执行、
 * MEDIUM 人工审核、HIGH 强制人工审核 + 资源限制。</p>
 */
public enum SqlGuardRiskLevel {

    /** 禁止：SELECT * / DML DDL / 未知表等 */
    BLOCK("BLOCK", "禁止"),
    /** 低风险：单表确定查询/简单聚合 + 显式 LIMIT，自动只读执行 */
    LOW("LOW", "低风险"),
    /** 中风险：多表 JOIN/子查询/复杂条件，人工审核 */
    MEDIUM("MEDIUM", "中风险"),
    /** 高风险：大表全扫/敏感字段命中/多表大 JOIN，强制人工审核 */
    HIGH("HIGH", "高风险");

    private final String code;
    private final String desc;

    SqlGuardRiskLevel(String code, String desc) {
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
     * 大小写不敏感解析；未知返回 null（由调用方校验）。
     */
    public static SqlGuardRiskLevel parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        for (SqlGuardRiskLevel level : values()) {
            if (level.code.equals(upper) || level.name().equals(upper)) {
                return level;
            }
        }
        return null;
    }
}
