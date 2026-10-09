package com.agenthub.ai.rcagent.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 审核任务（kb_review_task）：知识审核中心任务（创建→审批→联动知识状态）。
 * 非"知识对象"，不参与检索过滤。
 */
@Data
@TableName(value = "kb_review_task")
public class KbReviewTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dsId;

    /** 任务类型（见 ReviewTaskType） */
    private String taskType;

    /** 目标对象类型（见 TargetObjectType） */
    private String targetObjectType;

    /** 目标对象标识 JSON：{objectId, desc} */
    private String targetObject;

    /** 结构化变更建议 JSON：{operation, patch} */
    private String suggestedChange;

    /** 证据（采集/反馈/diff 引用） */
    private String evidence;

    /** 任务状态：pending/approved/rejected */
    private String status;

    /** 审核人（BR-003 必填，不可匿名） */
    private String reviewer;

    /** 审核意见（拒绝必填） */
    private String reviewComment;

    /** 审核时间 */
    private Date reviewedAt;

    private Date createTime;

    private Date updateTime;
}
