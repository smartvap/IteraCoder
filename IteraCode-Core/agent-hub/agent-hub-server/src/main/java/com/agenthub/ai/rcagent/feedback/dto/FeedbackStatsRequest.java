package com.agenthub.ai.rcagent.feedback.dto;

import lombok.Data;

/**
 * 反馈运营统计入参（接口4 POST /api/v1/rca/feedback/stats）。
 */
@Data
public class FeedbackStatsRequest {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 起始时间 yyyy-MM-dd HH:mm:ss */
    private String startTime;

    /** 结束时间 yyyy-MM-dd HH:mm:ss */
    private String endTime;
}
