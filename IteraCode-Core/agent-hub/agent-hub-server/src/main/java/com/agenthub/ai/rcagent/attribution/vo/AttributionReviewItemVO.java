package com.agenthub.ai.rcagent.attribution.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.Date;

/**
 * 归因审核待办/留痕视图（api-contract AttributionReviewItemVO，审核台）。
 *
 * <p>待审项（status=PENDING）decision/comment 为空；已处理项携带 decision/comment/reviewer_id。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionReviewItemVO {

    /** 审核记录业务 ID（待审 QUERY 时为 query_id，待审 CONCLUSION 时为 task_id） */
    private String reviewId;

    /** 归属任务 */
    private String taskId;

    /** 审核对象：QUERY / CONCLUSION */
    private String reviewObject;

    /** QUERY 时对应 query_id */
    private String queryId;

    /** 被审核对象风险等级 */
    private String riskLevel;

    /** 审核决策：APPROVE/REJECT（待审为空） */
    private String decision;

    /** 审核意见（打回必填） */
    private String comment;

    /** 审核人 */
    private Long reviewerId;

    /** 数据源 */
    private String dsId;

    /** 指标名（辅助展示） */
    private String metricName;

    /** SQL 脱敏抽象（QUERY 时展示） */
    private String sqlAbstract;

    /** 查询类型（QUERY 时展示） */
    private String queryType;

    /** 结论摘要（CONCLUSION 待审时展示） */
    private String conclusionSummary;

    /** 待办状态：PENDING/DONE */
    private String status;

    /** 创建时间 */
    private Date createTime;
}
