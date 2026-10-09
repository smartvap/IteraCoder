package com.agenthub.ai.rcagent.sqlguard.vo;

import com.agenthub.ai.rcagent.sqlguard.model.ValidationReport;
import lombok.Data;

/**
 * 调试-全流程验证响应（HTTP 接口 1 data）。
 */
@Data
public class SqlGuardValidateResultVO {

    /** 五级报告 */
    private ValidationReport report;

    /** 审计流水 ID（sql_guard_audit_log.id） */
    private Long auditId;
}
