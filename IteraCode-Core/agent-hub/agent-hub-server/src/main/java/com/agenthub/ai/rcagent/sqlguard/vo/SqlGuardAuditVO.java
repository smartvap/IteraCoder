package com.agenthub.ai.rcagent.sqlguard.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 审计流水视图（HTTP 接口 4 data.records[]）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SqlGuardAuditVO {

    private Long id;

    private String dsId;

    private String threadId;

    private String sqlFingerprint;

    private String sqlAbstract;

    private String statementType;

    private String dbType;

    /** 涉及表名 JSON 数组文本 */
    private List<String> involvedTables;

    private String riskLevel;

    private String verdict;

    private String blockReason;

    private String levelL1Result;

    private String levelL2Result;

    private String levelL3Result;

    private String levelL4Result;

    private String levelL5Result;

    private String levelsSummary;

    private String fixSuggestion;

    private Long costMs;

    private Integer resultRowCount;

    private Integer truncated;

    private String guardStatus;

    private Date createTime;
}
