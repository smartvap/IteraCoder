package com.agenthub.ai.rcagent.kbcrawler.constant;

/**
 * 运行时 SQL 证据来源（data-model 数据字典 SqlEvidenceSource）。
 */
public enum SqlEvidenceSource {

    /** MyBatis 拦截器（主通道，跨库统一，默认关闭由接入方启用） */
    RUNTIME_INTERCEPTOR("runtime-interceptor"),
    /** Oracle v$sql 辅助证据（权限不足降级） */
    ORACLE_V_SQL("oracle-v$sql"),
    /** MySQL performance_schema 辅助证据（权限不足降级） */
    MYSQL_PERF_SCHEMA("mysql-perf-schema"),
    /** APM 日志（SkyWalking 等，预留） */
    APM("apm");

    private final String value;

    SqlEvidenceSource(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (SqlEvidenceSource s : values()) {
            if (s.value.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
