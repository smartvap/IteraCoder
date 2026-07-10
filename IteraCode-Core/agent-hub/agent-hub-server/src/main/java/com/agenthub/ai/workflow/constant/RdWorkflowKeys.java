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
}
