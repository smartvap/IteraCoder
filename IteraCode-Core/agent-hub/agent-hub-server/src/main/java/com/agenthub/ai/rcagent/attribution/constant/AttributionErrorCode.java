package com.agenthub.ai.rcagent.attribution.constant;

/**
 * attribution 模块业务错误码（44101~44109，api-contract.md §错误码定义）。
 *
 * <p>按 api-contract 定义，不改存量 base.common.ErrorCode 枚举；使用方式：
 * {@code throw new BusinessException(AttributionErrorCode.XXX, "提示")}（HTTP/SPI 层桥接）。</p>
 */
public final class AttributionErrorCode {

    private AttributionErrorCode() {
    }

    /** 指标口径缺失或不可用（BR-001：无 verified/active kb_metric 定义） */
    public static final int METRIC_DEFINITION_MISSING = 44101;

    /** 归因参数非法（ds_id/指标/时间窗/维度/触发方式非法、时间范围重叠或无基期等） */
    public static final int PARAM_ILLEGAL = 44102;

    /** 归因 SQL 被安全拦截（module-006 verdict=BLOCK，该归因路径禁止） */
    public static final int SQL_BLOCKED = 44103;

    /** 归因任务不存在/状态非法（getTask/followUp/cancel/decideReview 目标状态不允许） */
    public static final int TASK_NOT_FOUND_OR_STATE = 44104;

    /** 依赖服务不可用（module-002/004/005/006 调用失败；message 不含连接细节） */
    public static final int DEPENDENCY_UNAVAILABLE = 44105;

    /** 审核状态冲突/重复审核（幂等冲突） */
    public static final int REVIEW_IDEMPOTENT_CONFLICT = 44106;

    /** 归因成本超限（BR-004：下钻层数或查询次数达上限） */
    public static final int LIMIT_REACHED = 44107;

    /** 结论修正超限（结论打回重试轮次超过上限） */
    public static final int REWRITE_LIMIT_REACHED = 44108;

    /** ds_id 缺失或隔离冲突（任务与请求 ds_id 不一致） */
    public static final int DS_ID_MISSING_OR_CONFLICT = 44109;
}
