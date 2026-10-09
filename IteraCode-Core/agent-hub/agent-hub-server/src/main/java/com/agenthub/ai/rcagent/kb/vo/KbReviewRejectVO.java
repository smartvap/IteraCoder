package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

/**
 * 审核拒绝响应 VO（接口15）。
 */
@Data
public class KbReviewRejectVO {

    private Long taskId;

    private String status;

    /** reopen_suggested=true 时新任务 id */
    private Long newTaskId;
}
