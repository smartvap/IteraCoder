package com.agenthub.ai.dbaccess.exception;

/**
 * db-access-common 模块自有错误枚举。
 *
 * <p>本模块为底层库模块，刻意不依赖 base.common.ErrorCode（解耦约束），由调用方模块（server / 上层）负责
 * 与 BaseResponse 桥接。错误码取值与 api-contract.md 错误码表保持一致。</p>
 */
public enum DbAccessErrorCode {

    /** 成功（正常返回，供枚举语义完整使用） */
    SUCCESS(0, "成功"),

    /** 未知数据源：resolve/getConfig 未找到 ds_id */
    UNKNOWN_DS(1001, "未知数据源"),

    /** 数据源停用：命中 status=0 / is_deleted=1 的数据源 */
    DS_DISABLED(1002, "数据源已停用"),

    /** 数据源未就绪：连接池初始化中/健康检查失败/密码引用环境变量缺失，快速失败不阻塞其他 ds_id */
    DS_NOT_READY(1003, "数据源未就绪"),

    /** 无可用 Provider：目标 db_type 无注册 Provider 且 JdbcFallback 也失败 */
    NO_PROVIDER(1101, "无可用元数据 Provider"),

    /** 方言不支持：未注册方言配置 */
    DIALECT_UNSUPPORTED(1102, "数据库方言不支持"),

    /** 白名单拒绝：查询表/字段不在数据源白名单（fail-closed） */
    WHITELIST_DENIED(1201, "白名单拒绝访问"),

    /** 敏感字段拒绝：敏感策略 DENY 且查询命中 HIGH 敏感字段 */
    SENSITIVE_DENIED(1202, "命中敏感字段，访问被拒绝"),

    /** 行数超限：服务端强拒绝时使用（正常场景以 QueryResult.truncated=true 返回为主） */
    ROW_LIMIT_EXCEEDED(1203, "查询行数超过限制"),

    /** 执行超时：超过 maxSeconds */
    TIMEOUT(1204, "查询执行超时"),

    /**
     * 连接失败：目标库不可达/凭据错误（message 不含地址凭据）。
     *
     * <p>类别归因入口（注释级说明，枚举值与 {@link #getMessage()} 不变）：如需细分失败原因，可复用
     * {@code com.agenthub.ai.dbaccess.diagnostic.ConnectionFailureClassifier} 按 SQLState/vendor errorCode 归类；
     * 本模块默认不接入该分类器，仍保持返回码与 message 不变。</p>
     */
    CONNECTION_FAILED(1205, "数据库连接失败"),

    /** SQL 被拒：SELECT * 策略拒绝或非 SELECT 语句（DML/DDL 尝试） */
    SQL_REJECTED(1302, "SQL 已被拒绝"),

    /** 参数错误：ds_id 为空/非法、SQL 为空、limits 非法、jdbc_url 含内嵌账密等 */
    PARAM_INVALID(1301, "参数错误"),

    /** 系统异常：未预期运行时异常（由模块异常包装） */
    SYSTEM_ERROR(1500, "系统异常");

    private final int code;
    private final String message;

    DbAccessErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    /**
     * 按 code 反查枚举。
     *
     * @param code 错误码
     * @return 枚举；未命中返回 {@code null}
     */
    public static DbAccessErrorCode of(int code) {
        for (DbAccessErrorCode item : values()) {
            if (item.code == code) {
                return item;
            }
        }
        return null;
    }
}
