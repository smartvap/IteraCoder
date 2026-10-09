package com.agenthub.ai.rcagent.sqlguard.dto;

import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import lombok.Data;

/**
 * 调试-全流程验证请求（HTTP 接口 1 POST /api/v1/sqlguard/debug/validate）。
 */
@Data
public class SqlGuardValidateRequest {

    /** 数据源实例 ID */
    private String dsId;

    /** 待验证 SQL */
    private String sql;

    /** 库类型（缺省按 ds_id 解析） */
    private String dbType;

    /** SQL 规划 Plan（同 SqlPlanView） */
    private SqlPlanView plan;
}
