package com.agenthub.ai.rcagent.feedback.dto;

import lombok.Data;

/**
 * 触发回写入参（接口7 POST /api/v1/rca/feedback/{flowId}/writeback）。
 * flowId 走 PathVariable，请求体仅 force。
 */
@Data
public class WritebackRequest {

    /** 是否忽略状态强制重试（默认 false；仅调试） */
    private Boolean force = false;

    public boolean force() {
        return Boolean.TRUE.equals(force);
    }
}
