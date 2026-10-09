package com.agenthub.ai.rcagent.attribution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 归因审核留痕（rcagent_attribution_review_log）。
 *
 * <p>归因人工审核决策记录（FR-007）：覆盖归因查询审核（MED/HIGH 放行）与归因结论审核（通过/打回）。
 * module-001 会话级审计写入其 rcagent_review_audit，本表保留模块独立留痕。</p>
 */
@Data
@TableName(value = "rcagent_attribution_review_log")
public class RcagentAttributionReviewLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 审核记录业务 ID（attrrv_ + 时间戳 + 序号） */
    private String reviewId;

    /** 归属归因任务 */
    private String taskId;

    /** 关联工作流会话（module-001 审计联动） */
    private String threadId;

    /** 审核对象：QUERY / CONCLUSION */
    private String reviewObject;

    /** QUERY 审核时对应 query_id */
    private String queryId;

    /** 被审核对象原始风险等级 */
    private String riskLevel;

    /** 审核决策：APPROVE / REJECT */
    private String decision;

    /** 审核意见（打回必填） */
    private String comment;

    /** 审核人（BaseContext.getCurrentId()） */
    private Long reviewerId;

    /** 第几轮审核（打回修正后重审递增） */
    private Integer reviewRound;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 逻辑删除标记 */
    private Integer isDeleted;
}
