package com.agenthub.ai.rcagent.engine.constant;

/**
 * RCAgent 人工审核决策（与 rd 的 RdWorkflowReviewDecision 语义对齐，但独立定义）。
 */
public enum RcaReviewDecision {
    /** 审核通过，进入执行/继续 */
    APPROVED,
    /** 打回，带意见回退重生成/重跑 */
    SENT_BACK,
    /** 拒绝/终止会话 */
    TERMINATED
}
