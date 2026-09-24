package com.agenthub.ai.datasource.vo;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 审计记录视图（api-contract DsAuditVO）。changeDetail 已脱敏（敏感字段只记 changed 标记）。
 */
@Data
public class DsAuditVO {

    private Long id;

    private String dsId;

    /** REGISTER/UPDATE/ENABLE/DISABLE/DELETE/TEST_CONNECTION/HEALTH_CHECK/SPACE_BIND */
    private String action;

    private Long operatorId;

    private String operatorName;

    /** 变更摘要 */
    private String changeSummary;

    /** 变更明细（脱敏） */
    private List<AuditChange> changeDetail;

    /** SUCCESS/FAIL */
    private String bizResult;

    /** 失败原因（脱敏） */
    private String errorMessage;

    private Date createTime;
}
