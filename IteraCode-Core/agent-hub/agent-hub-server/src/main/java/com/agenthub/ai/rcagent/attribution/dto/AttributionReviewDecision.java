package com.agenthub.ai.rcagent.attribution.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * 审核决策入参（POST /api/v1/attribution/review/decision，api-contract 接口 6，FR-004/007）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionReviewDecision {

    /** 归因任务 */
    private String taskId;

    /** 审核对象：QUERY / CONCLUSION */
    private String reviewObject;

    /** 审核决策：APPROVE / REJECT */
    private String decision;

    /** review_object=QUERY 时待决策查询 id（一次可多笔，逐笔落留痕） */
    private List<String> queryIds;

    /** REJECT 必填审核意见（≤1000） */
    private String comment;

    /** REJECT 后处理：QUERY→RETRY/SKIP；CONCLUSION→REWRITE/DEGRADE */
    private String actionOnReject;

    /** 审核人（默认 BaseContext） */
    private Long reviewerId;
}
