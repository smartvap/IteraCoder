package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * S-2 validate 结果载体（report + 审计流水 ID）。
 *
 * <p>HTTP 接口 1 debug/validate 契约要求返回 {@code data.auditId}
 * （sql_guard_audit_log.id）；validate() 内部追加审计后回传落库主键，
 * 供 Controller 组装响应闭环追踪。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlGuardValidateOutcome {

    /** 五级验证报告 */
    private ValidationReport report;

    /** 本次验证追加的审计流水 ID（sql_guard_audit_log.id，可空=审计落库失败） */
    private Long auditId;
}
