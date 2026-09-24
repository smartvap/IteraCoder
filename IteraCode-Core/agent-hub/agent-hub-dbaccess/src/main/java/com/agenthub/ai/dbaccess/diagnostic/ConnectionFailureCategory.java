package com.agenthub.ai.dbaccess.diagnostic;

/**
 * 数据源连接失败原因类别（FR-BE-001~003，api-contract §4.3 / data-model §3.1）。
 *
 * <p>每个类别承载：{@code code}（响应字段 {@code failureCategory} 取值）+ 中文类别名 + 中文处置建议。
 * 类别与建议均为<b>静态枚举常量</b>，禁止动态拼接 host / 库名 / 账号 / 密码 / 连接串原文 / 驱动原始 message
 * （AGENTS.md 4.x/5.x 脱敏要求，避免把连接凭据随诊断文案外泄）。</p>
 *
 * <p>本枚举为响应/日志/健康记录的「类别码」来源；类别码不落新列，落库表现并入既有
 * {@code error_message} 文本（data-model §4.1）。</p>
 */
public enum ConnectionFailureCategory {

    /** 网络不可达 / 超时：SQLState 08* 或 message 含 Communications link failure / connect timed out / SocketTimeout / UnknownHostException */
    NETWORK_UNREACHABLE("NETWORK_UNREACHABLE", "网络不可达 / 超时",
            "请确认主机地址、端口与网络可达性（含防火墙/白名单），并确认网络未超时"),

    /** 连接被拒绝：SQLState 08001 且 message 含 Connection refused，或 message 直接含 Connection refused */
    CONNECTION_REFUSED("CONNECTION_REFUSED", "连接被拒绝",
            "请确认目标端口已监听且服务已启动（OceanBase 常见为 OBPROXY 端口配置错误）"),

    /** 数据库（库名）不存在：vendor errorCode 1049 / message 含 Unknown database */
    DATABASE_NOT_FOUND("DATABASE_NOT_FOUND", "数据库（库名）不存在",
            "请检查连接串末尾的默认库名是否正确（应填真实可访问库）"),

    /** 库无访问权限：vendor errorCode 1044 / message 含 Access denied for user ... to database */
    DATABASE_ACCESS_DENIED("DATABASE_ACCESS_DENIED", "库无访问权限",
            "请为该只读账号授予目标库的访问权限（仅 SELECT，保持只读）"),

    /** 账号或密码错误（认证失败）：vendor errorCode 1045 / message 含 Access denied for user（须在 1044 之后判定） */
    AUTH_FAILED("AUTH_FAILED", "账号或密码错误（认证失败）",
            "请确认账号、密码是否正确；OceanBase 账号需形如 用户名@租户（租户名需与目标租户一致）"),

    /** 连接配置未就绪：应用侧判定（${ENV} 占位解析失败），非 SQLException 归类 */
    CONFIG_UNRESOLVED("CONFIG_UNRESOLVED", "连接配置未就绪",
            "请确认密码引用的环境变量已在服务端注入，或改填正确的 ${ENV} 引用"),

    /** 未知（兜底）：其余不可归类；强制兜底，永不返回 null */
    UNKNOWN("UNKNOWN", "未知（兜底）",
            "连接失败原因无法归类，请查看服务端日志中的失败类别与耗时，或联系数据源管理员");

    private final String code;
    private final String text;
    private final String suggestion;

    ConnectionFailureCategory(String code, String text, String suggestion) {
        this.code = code;
        this.text = text;
        this.suggestion = suggestion;
    }

    /** 类别码（响应字段 failureCategory 取值） */
    public String getCode() {
        return code;
    }

    /** 类别中文名（响应字段 failureCategoryText 取值） */
    public String getText() {
        return text;
    }

    /** 中文处置建议（脱敏静态常量，禁止动态拼接凭据/地址/库名） */
    public String getSuggestion() {
        return suggestion;
    }

    /**
     * 组合脱敏失败文案：{@code 类别中文 + 建议}，用于既有 {@code errorMessage} 字段（语义不变、内容细化）。
     *
     * <p>文案全部来自本枚举静态常量，不含任何 host / 库名原文 / 账号 / 密码 / 连接串原文 / 驱动原始 message。</p>
     *
     * @return 形如「账号或密码错误（认证失败）：请确认账号、密码是否正确；……」
     */
    public String toErrorMessage() {
        return text + "：" + suggestion;
    }
}
