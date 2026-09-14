package com.agenthub.ai.workflow.dto;

import com.agenthub.ai.workflow.constant.RdWorkflowReviewDecision;
import lombok.Data;

@Data
public class RdWorkflowResumeRequest {
    /** 工作流线程 ID */
    private String threadId;
    /** 人工审核决策 */
    private RdWorkflowReviewDecision reviewDecision;
    /** 审核备注（可选） */
    private String comment;
    /** 前端选择的模型名（可选，恢复执行后代码生成等节点使用） */
    private String modelName;
}
