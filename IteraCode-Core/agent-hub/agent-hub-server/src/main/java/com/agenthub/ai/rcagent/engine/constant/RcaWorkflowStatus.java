package com.agenthub.ai.rcagent.engine.constant;

/**
 * RCAgent 工作流会话状态（六态状态机，与 rd 的 RdWorkflowStatus 完全隔离）。
 *
 * <p>流转关系：RUNNING → WAITING_REVIEW / COMPLETED / TERMINATED / FAILED；
 * WAITING_REVIEW → RUNNING（审核通过）/ SENT_BACK（打回重生成）/ TERMINATED；
 * SENT_BACK → RUNNING（重跑）；FAILED → RUNNING（recover）。</p>
 */
public enum RcaWorkflowStatus {
    /** 执行中 */
    RUNNING,
    /** 等待人工审核（interruptAfter 中断） */
    WAITING_REVIEW,
    /** 已打回（审核驳回，回退重生成/重跑） */
    SENT_BACK,
    /** 完成（结果/归因结论已返回） */
    COMPLETED,
    /** 已终止（人工拒绝/系统终止） */
    TERMINATED,
    /** 失败（可 recover） */
    FAILED
}
