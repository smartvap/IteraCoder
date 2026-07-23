package com.agenthub.ai.workflow.constant;

/**
 * 研发工作流状态键常量
 */
public final class RdWorkflowKeys {

    private RdWorkflowKeys() {
    }

    public static final String REQUIREMENT = "requirement";
    public static final String DECOMPOSITION_RESULT = "decomposition_result";
    public static final String PARALLEL_REASONING_RESULT = "parallel_reasoning_result";
    public static final String REVIEW_CONTENT = "review_content";
    public static final String REVIEW_DECISION = "review_decision";
    public static final String GENERATED_CODE = "generated_code";
    public static final String HARNESS_RESULT = "harness_result";
    public static final String VALIDATION_PASSED = "validation_passed";
    public static final String REPAIR_COUNT = "repair_count";
    public static final String WORKFLOW_STATUS = "workflow_status";
    public static final String WORKFLOW_MESSAGE = "workflow_message";
    /** 人工审核反馈（驳回备注），全程不被任何节点覆盖，与 requirement 同等生命周期 */
    public static final String REVIEW_FEEDBACK = "review_feedback";

    public static final int MAX_REPAIR_ITERATIONS = 5;

    /**
     * 判断拆解结果是否为非研发需求（三层兜底）。
     * <p>
     * 1. 标准标记：[NOT_DEV_REQ] 大小写不敏感匹配，且不含任务列表特征（防 LLM 误用）
     * 2. 特征兜底：输出极短（&lt;80 字符）且不包含结构化内容（表格、标题、代码块）
     */
    public static boolean isNotDevReq(String decompositionResult) {
        if (decompositionResult == null || decompositionResult.isBlank()) {
            return false;
        }
        String upper = decompositionResult.strip().toUpperCase();
        // 1. 标准标记匹配：包含 NOT_DEV_REQ 且无任务列表特征
        //    防止 LLM 误在正常输出前加 [NOT_DEV_REQ]（如 "这是一个软件研发需求..."）
        if (upper.contains("NOT_DEV_REQ")) {
            boolean hasTaskFeatures = decompositionResult.contains("|") || decompositionResult.contains("###")
                    || decompositionResult.length() > 200;
            if (!hasTaskFeatures) {
                return true;
            }
            // 有 NOT_DEV_REQ 但同时也像正常任务列表 → LLM 混淆，不拦截
        }
        // 2. 特征兜底：输出短且无结构化标记（表格 |、标题 #、代码块 ```）
        if (decompositionResult.strip().length() < 80
                && !decompositionResult.contains("|")
                && !decompositionResult.contains("#")
                && !decompositionResult.contains("```")) {
            return true;
        }
        return false;
    }
}
