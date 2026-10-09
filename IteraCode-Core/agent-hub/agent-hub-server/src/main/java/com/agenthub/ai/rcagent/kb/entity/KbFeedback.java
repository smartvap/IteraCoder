package com.agenthub.ai.rcagent.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 反馈记录（kb_feedback）：用户反馈/纠错记录，供 feedback-loop 驱动闭环。
 * 非"知识对象"，不参与检索过滤，也不带可信度四元组。
 */
@Data
@TableName(value = "kb_feedback")
public class KbFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dsId;

    /** 关联会话/工作流 thread_id */
    private String sessionId;

    /** 原始问题 */
    private String question;

    /** 生成的 SQL */
    private String generatedSql;

    /** 用户修正 SQL */
    private String modifiedSql;

    /** 修改类型（feedback-loop 写入） */
    private String changeType;

    /** 反馈类型：POSITIVE/NEGATIVE/CORRECTION/EXPLANATION */
    private String feedbackType;

    /** 用户文本反馈 */
    private String userComment;

    /** 审核结论：PENDING/APPROVED/REJECTED */
    private String reviewConclusion;

    /** 处理状态：PENDING/ANALYZING/REVIEWING/APPROVED/REJECTED/WRITTEN_BACK/CLOSED */
    private String status;

    private Date createTime;

    private Date updateTime;
}
