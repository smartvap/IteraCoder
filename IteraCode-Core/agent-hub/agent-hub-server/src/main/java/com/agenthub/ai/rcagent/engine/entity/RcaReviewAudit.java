package com.agenthub.ai.rcagent.engine.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * RCAgent 人工审核审计记录：SQL 草案/归因结论的审核对象、风险等级、决策、意见、审核人。
 *
 * <p>审核内容（SQL 草案/结论）仅限有权限角色可见，因此与业务元数据分离独立成表。</p>
 */
@TableName(value = "rcagent_review_audit")
@Data
public class RcaReviewAudit {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联会话（逻辑外键，不建物理外键） */
    private String threadId;

    /** 审核对象类型：SQL（SQL 草案）/ ATTRIBUTION（归因结论） */
    private String reviewObject;

    /** 本次审核对象风险等级快照：LOW/MEDIUM/HIGH/BLOCK */
    private String riskLevel;

    /** SQL 草案原文（review_object=SQL 时记录；仅落审核审计库） */
    private String sqlDraft;

    /** 归因结论原文（review_object=ATTRIBUTION 时记录） */
    private String conclusion;

    /** 审核决策：APPROVED / SENT_BACK / TERMINATED */
    private String decision;

    /** 审核意见 */
    private String comment;

    /** 审核人 ID */
    private Long reviewerId;

    /** 审核时间 */
    private Date createTime;
}
