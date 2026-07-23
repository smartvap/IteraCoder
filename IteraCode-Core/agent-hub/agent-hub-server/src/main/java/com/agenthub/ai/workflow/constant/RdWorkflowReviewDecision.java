package com.agenthub.ai.workflow.constant;

/**
 * 人工审核决策
 */
public enum RdWorkflowReviewDecision {
    /** 审核通过，进入正式代码生成 */
    APPROVED,
    /** 审核驳回，回到需求拆解 */
    SENT_BACK,
    /** 审核拒绝，终止流程 */
    TERMINATED
}
