package com.agenthub.ai.workflow.exception;

/**
 * 工作流基础设施异常（Git、Docker、沙箱等非 LLM 层错误）。
 * <p>
 * 抛出此异常时，{@code toFriendlyErrorMessage} 直接使用异常消息原文，
 * 不做 LLM API 错误匹配（如 403 → "API Key 额度不足"）。
 * <p>
 * 用法：节点在 catch 到底层异常后包装为 {@code WorkflowInfraException} 向上抛：
 * <pre>{@code
 * throw new WorkflowInfraException("Git 推送失败: " + detail, e);
 * }</pre>
 */
public class WorkflowInfraException extends RuntimeException {

    public WorkflowInfraException(String message) {
        super(message);
    }

    public WorkflowInfraException(String message, Throwable cause) {
        super(message, cause);
    }
}
