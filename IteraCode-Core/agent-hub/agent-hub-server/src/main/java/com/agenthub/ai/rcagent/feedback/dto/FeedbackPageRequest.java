package com.agenthub.ai.rcagent.feedback.dto;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

/**
 * 反馈闭环分页入参（接口3 POST /api/v1/rca/feedback/page）。
 */
@Data
public class FeedbackPageRequest {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 闭环状态过滤（多值逗号分隔可选，如 REVIEWING,APPROVED） */
    private String status;

    /** 反馈类型过滤 */
    private String feedbackType;

    /** 修改类型过滤 */
    private String changeType;

    /** 会话追溯 */
    private String sessionId;

    /** 创建开始时间 yyyy-MM-dd HH:mm:ss */
    private String startTime;

    /** 创建结束时间 yyyy-MM-dd HH:mm:ss */
    private String endTime;

    private Integer page = 1;

    private Integer pageSize = 10;

    /** 逗号分隔 status 解析为多值 */
    public List<String> statusList() {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        return Arrays.stream(status.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public int page() {
        return page == null || page < 1 ? 1 : page;
    }

    public int pageSize() {
        return pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
    }
}
