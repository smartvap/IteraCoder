package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

/**
 * 审核批准响应 VO（接口14）。
 */
@Data
public class KbReviewApproveVO {

    private Long taskId;

    private String status;

    /** 目标知识是否被联动更新 */
    private Boolean targetUpdated;
}
