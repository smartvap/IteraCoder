package com.agenthub.ai.rcagent.engine.dto;

import com.agenthub.ai.rcagent.engine.constant.RcaReviewDecision;
import lombok.Data;

/**
 * 人工审核恢复入参（POST /api/v1/rcagent/resume）。
 */
@Data
public class RcaWorkflowResumeRequest {

    /** 会话线程 ID */
    private String threadId;

    /** 审核决策：APPROVED / SENT_BACK / TERMINATED */
    private RcaReviewDecision reviewDecision;

    /** 审核意见/打回备注（≤1024） */
    private String comment;
}
