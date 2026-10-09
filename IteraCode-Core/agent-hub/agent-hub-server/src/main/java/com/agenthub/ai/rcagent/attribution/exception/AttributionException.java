package com.agenthub.ai.rcagent.attribution.exception;

import com.agenthub.ai.base.exception.BusinessException;

/**
 * attribution 模块业务异常（继承 base {@link BusinessException}，接入全局异常体系）。
 *
 * <p>按 spec/api-contract：归因结果不是"执行失败即异常"——无异常/口径问题/达上限等均结构化返回；
 * 仅参数非法、任务不存在/状态非法、依赖模块不可用、资源层拒绝等业务异常才抛本异常
 * （code 复用 {@link com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode}）。
 * 继承 {@link BusinessException} 后由 base {@code GlobalExceptionHandler#businessExceptionHandler}
 * 按 code 返回 441xx（任务不存在 44104 等），不再退化为 50000 SYSTEM_ERROR。
 * 安全约束（AGENTS.md 5.x）：message 禁止携带 SQL 原文、question 全量、连接串/账号/密码/Token。</p>
 */
public class AttributionException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public AttributionException(int code, String message) {
        super(code, message);
    }

    public AttributionException(int code, String message, Throwable cause) {
        super(code, message);
        initCause(cause);
    }

    /** 参数错误快捷工厂（复用 44102 语义） */
    public static AttributionException param(String message) {
        return new AttributionException(
                com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode.PARAM_ILLEGAL, message);
    }

    /** 依赖服务不可用快捷工厂（44105） */
    public static AttributionException dependency(String message) {
        return new AttributionException(
                com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode.DEPENDENCY_UNAVAILABLE, message);
    }
}
