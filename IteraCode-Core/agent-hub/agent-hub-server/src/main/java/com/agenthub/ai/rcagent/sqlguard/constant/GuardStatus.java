package com.agenthub.ai.rcagent.sqlguard.constant;

/**
 * 审计状态（sql_guard_audit_log.guard_status，data-model.md 数据字典 GuardStatus）。
 */
public enum GuardStatus {

    /** 仅完成验证/分级未执行 */
    VALIDATED("VALIDATED"),
    /** 已完成受保护执行 */
    EXECUTED("EXECUTED"),
    /** 被 BLOCK/敏感拒绝/只读层拒绝 */
    REJECTED("REJECTED"),
    /** 执行超时终止 */
    TIMEOUT("TIMEOUT");

    private final String code;

    GuardStatus(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
