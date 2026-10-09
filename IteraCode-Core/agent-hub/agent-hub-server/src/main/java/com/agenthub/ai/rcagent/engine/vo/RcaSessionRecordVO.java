package com.agenthub.ai.rcagent.engine.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * RCAgent 会话历史记录视图（GET /sessions 分页 records）。
 */
@Data
@Builder
public class RcaSessionRecordVO {

    private String threadId;

    private String dsId;

    private String question;

    private String intentType;

    private String status;

    private String riskLevel;

    private String reviewDecision;

    private String resultSummary;

    private String remark;

    private Date createTime;

    private Date updateTime;
}
