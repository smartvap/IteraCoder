package com.agenthub.ai.rcagent.kb.constant;

/**
 * kb 模块业务错误码（42001~42007）。
 *
 * <p>按 api-contract.md 定义，不改存量 base.common.ErrorCode 枚举；
 * 使用方式：{@code throw new BusinessException(KbErrorCode.TARGET_NOT_EXIST, "目标知识不存在")}。</p>
 */
public final class KbErrorCode {

    private KbErrorCode() {
    }

    /** 目标知识不存在（confidence/structure-change/审核联动定位不到知识行） */
    public static final int TARGET_NOT_EXIST = 42001;

    /** ds_id 缺失或隔离冲突 */
    public static final int DS_ID_MISSING_OR_CONFLICT = 42002;

    /** 状态不允许（对 deprecated/非 pending 对象做不允许的状态流转） */
    public static final int STATUS_NOT_ALLOWED = 42003;

    /** 审核任务幂等冲突（已 approved/rejected 重复审批） */
    public static final int REVIEW_IDEMPOTENT_CONFLICT = 42004;

    /** 目标对象类型非法 */
    public static final int TARGET_TYPE_ILLEGAL = 42005;

    /** LLM 知识未人工确认 */
    public static final int LLM_NOT_CONFIRMED = 42006;

    /** 索引/反查失败（结构变更影响分析异常） */
    public static final int IMPACT_ANALYSIS_FAILED = 42007;
}
