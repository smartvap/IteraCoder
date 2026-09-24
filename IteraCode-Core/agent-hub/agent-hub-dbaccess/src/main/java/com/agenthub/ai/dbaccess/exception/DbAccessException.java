package com.agenthub.ai.dbaccess.exception;

/**
 * db-access-common 模块统一运行时异常。
 *
 * <p>安全约束（对齐 AGENTS.md 5.x）：message 禁止携带连接串/账号/密码/完整密钥/内嵌账密，
 * 任何方法签名、日志、异常 message 中不得出现 password/token/secret 明文。</p>
 */
public class DbAccessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public DbAccessException(DbAccessErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public DbAccessException(DbAccessErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public DbAccessException(DbAccessErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }

    public DbAccessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    /**
     * 参数错误快捷工厂。
     */
    public static DbAccessException param(String message) {
        return new DbAccessException(DbAccessErrorCode.PARAM_INVALID, message);
    }

    /**
     * 系统异常包装（对外 message 统一收敛，不暴露底层细节）。
     */
    public static DbAccessException system(String message, Throwable cause) {
        return new DbAccessException(DbAccessErrorCode.SYSTEM_ERROR, message, cause);
    }
}
