package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.util.Date;

/**
 * 反馈记录 VO（接口26 records）。
 */
@Data
public class KbFeedbackVO {

    private Long id;

    private String dsId;

    private String sessionId;

    private String question;

    private String generatedSql;

    private String modifiedSql;

    private String changeType;

    private String feedbackType;

    private String userComment;

    private String reviewConclusion;

    private String status;

    private Date createTime;

    private Date updateTime;
}
