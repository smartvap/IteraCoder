package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.util.Date;

/**
 * 审核任务 VO（接口12 records / 接口13 task）。
 */
@Data
public class KbReviewTaskVO {

    private Long id;

    /** 数据源实例 ID */
    private String dsId;

    private String taskType;

    private String targetObjectType;

    /** 目标对象标识 JSON */
    private String targetObject;

    /** 结构化建议 JSON */
    private String suggestedChange;

    private String evidence;

    private String status;

    private String reviewer;

    private String reviewComment;

    private Date reviewedAt;

    private Date createTime;

    private Date updateTime;

}
