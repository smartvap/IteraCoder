package com.agenthub.ai.rcagent.sqlguard.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 审计流水分页请求（HTTP 接口 4 POST /api/v1/sqlguard/audit/page）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SqlGuardAuditPageRequest {

    /** 精确数据源 */
    private String dsId;

    /** 会话 */
    private String threadId;

    /** BLOCK/LOW/MEDIUM/HIGH（可空） */
    private String riskLevel;

    /** ALLOW/REVIEW/BLOCK（可空） */
    private String verdict;

    /** VALIDATED/EXECUTED/REJECTED/TIMEOUT（可空） */
    private String guardStatus;

    /** create_time 区间起（yyyy-MM-dd HH:mm:ss） */
    private String startDate;

    /** create_time 区间止 */
    private String endDate;

    /** 页码默认 1 */
    private Integer page;

    /** 页大小默认 20 ≤100 */
    private Integer pageSize;

    public int page() {
        return page == null || page < 1 ? 1 : page;
    }

    public int pageSize() {
        return pageSize == null ? 20 : Math.min(100, Math.max(1, pageSize));
    }
}
