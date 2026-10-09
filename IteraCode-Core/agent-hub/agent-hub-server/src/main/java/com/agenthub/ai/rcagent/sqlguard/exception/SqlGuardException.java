package com.agenthub.ai.rcagent.sqlguard.exception;

/**
 * sqlguard 模块统一运行时异常（SPI 层基础设施异常通道）。
 *
 * <p>安全约束（AGENTS.md 5.x）：message 禁止携带 SQL 原文、敏感字面量、
 * db 连接串、账号/密码/Token 等凭据；依赖方失败仅输出收敛后的原因摘要。</p>
 */
public class SqlGuardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public SqlGuardException(int code, String message) {
        super(message);
        this.code = code;
    }

    public SqlGuardException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 参数错误快捷工厂（复用 SqlGuardErrorCode.PARAM_INVALID=40000 语义）。
     */
    public static SqlGuardException param(String message) {
        return new SqlGuardException(com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardErrorCode.PARAM_INVALID, message);
    }
}
