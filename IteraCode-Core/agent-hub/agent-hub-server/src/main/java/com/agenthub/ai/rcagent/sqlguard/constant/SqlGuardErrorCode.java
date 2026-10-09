package com.agenthub.ai.rcagent.sqlguard.constant;

/**
 * sqlguard 模块业务错误码（43001~43007，api-contract.md §错误码定义）。
 *
 * <p>不改存量 base.common.ErrorCode 枚举；使用方式：{@code throw new SqlGuardException(code, message)}
 * 或 {@code throw new BusinessException(SqlGuardErrorCode.XXX, message)}（HTTP 层桥接）。</p>
 */
public final class SqlGuardErrorCode {

    private SqlGuardErrorCode() {
    }

    /** SQL 被 BLOCK 禁止（SELECT * / DML/DDL / 未知表） */
    public static final int SQL_BLOCKED = 43001;

    /** L2 Schema 校验失败（表/列不存在、JOIN 字段无对应列、类型不匹配） */
    public static final int SCHEMA_CHECK_FAILED = 43002;

    /** 敏感字段拒绝（sensitivePolicy=DENY 且命中 HIGH 敏感字段） */
    public static final int SENSITIVE_DENIED = 43003;

    /** 策略配置非法（policy 保存校验失败：scope/ds_id/maxRows 越界等） */
    public static final int POLICY_ILLEGAL = 43004;

    /** 未找到适用策略 */
    public static final int POLICY_NOT_FOUND = 43005;

    /** 方言不支持（dialect 不在 DbType/方言白名单内或 Parser 无法识别） */
    public static final int DIALECT_UNSUPPORTED = 43006;

    /** 依赖服务不可用（db-access-common / kb-storage-review 调用失败） */
    public static final int DEPENDENCY_UNAVAILABLE = 43007;

    /** 参数非法（复用全局 40000 语义，SPI 层未走 HTTP 时使用） */
    public static final int PARAM_INVALID = 40000;
}
