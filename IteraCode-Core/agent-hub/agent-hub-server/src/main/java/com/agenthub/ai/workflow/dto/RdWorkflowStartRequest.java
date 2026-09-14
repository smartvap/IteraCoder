package com.agenthub.ai.workflow.dto;

import lombok.Data;

@Data
public class RdWorkflowStartRequest {
    /** 用户原始研发需求 */
    private String requirement;

    /** 前端选择的模型名称（如 qwen3:4b） */
    private String modelName;
}
