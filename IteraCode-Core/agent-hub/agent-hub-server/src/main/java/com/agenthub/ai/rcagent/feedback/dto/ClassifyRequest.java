package com.agenthub.ai.rcagent.feedback.dto;

import lombok.Data;

/**
 * 人工判定修改类型入参（接口6 POST /api/v1/rca/feedback/{flowId}/classify）。
 */
@Data
public class ClassifyRequest {

    /** 修改类型（ChangeType 枚举，含归因扩展） */
    private String changeType;

    /** 人工说明（写入 evidence） */
    private String comment;

    /** 是否立即生成建议并建审，默认 true */
    private Boolean buildSuggestion = true;

    public boolean buildSuggestion() {
        return buildSuggestion == null || buildSuggestion;
    }
}
