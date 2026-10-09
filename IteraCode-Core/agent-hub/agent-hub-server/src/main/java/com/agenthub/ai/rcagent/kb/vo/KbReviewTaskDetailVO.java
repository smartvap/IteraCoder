package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

/**
 * 审核任务详情响应 VO（接口13）。
 */
@Data
public class KbReviewTaskDetailVO {

    /** 任务完整字段 */
    private KbReviewTaskVO task;

    /** 目标对象当前详情（可为空） */
    private Object targetDetail;
}
