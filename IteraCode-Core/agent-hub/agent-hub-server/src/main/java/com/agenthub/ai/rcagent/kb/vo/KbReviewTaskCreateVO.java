package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

/**
 * 创建审核任务响应 VO（接口11）。
 */
@Data
public class KbReviewTaskCreateVO {

    private Long taskId;

    private String status;
}
