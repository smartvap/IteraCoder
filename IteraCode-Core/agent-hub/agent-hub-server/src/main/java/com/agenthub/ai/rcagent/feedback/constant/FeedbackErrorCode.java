package com.agenthub.ai.rcagent.feedback.constant;

/**
 * feedback 模块业务错误码（45001~45008）。
 *
 * <p>按 api-contract 定义，不改存量 base.common.ErrorCode 枚举；
 * 使用方式：{@code throw new BusinessException(FeedbackErrorCode.FLOW_NOT_EXIST, "...")}。</p>
 */
public final class FeedbackErrorCode {

    private FeedbackErrorCode() {
    }

    /** 反馈处理流不存在 */
    public static final int FLOW_NOT_EXIST = 45001;

    /** 重复反馈流（同一 (ds_id, feedback_id) 已存在处理流） */
    public static final int DUPLICATE_FLOW = 45002;

    /** 分析未完成/不可用 */
    public static final int ANALYSIS_NOT_READY = 45003;

    /** 状态不允许 */
    public static final int STATUS_NOT_ALLOWED = 45004;

    /** 依赖模块调用失败（module-004/006/llm-wiki） */
    public static final int DEPENDENCY_CALL_FAILED = 45005;

    /** 建议生成失败（KnowledgeBuilder 无可用 LLM 或结构化输出解析失败） */
    public static final int SUGGESTION_FAILED = 45006;

    /** 回写目标缺失（应转「新知识采集」建议而非直接回写） */
    public static final int WRITEBACK_TARGET_MISSING = 45007;

    /** 幂等冲突/重复执行 */
    public static final int IDEMPOTENT_CONFLICT = 45008;
}
