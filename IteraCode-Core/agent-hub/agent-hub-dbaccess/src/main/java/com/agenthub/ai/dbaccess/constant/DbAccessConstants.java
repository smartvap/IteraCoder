package com.agenthub.ai.dbaccess.constant;

import java.util.regex.Pattern;

/**
 * db-access-common 公共常量（不承载默认限制数值，限制默认值一律由 DbAccessProperties 注入，禁止硬编码魔法值）。
 */
public final class DbAccessConstants {

    private DbAccessConstants() {
    }

    /** ds_id 格式：小写字母/数字开头，允许小写字母/数字/中划线/下划线，长度 ≤64 */
    public static final String DS_ID_PATTERN = "^[a-z0-9][a-z0-9-_]{0,63}$";

    /** SQL 最大长度（api-contract：≤65535） */
    public static final int SQL_MAX_LENGTH = 65535;

    /** SQL 首关键字白名单（只读） */
    public static final String[] ALLOWED_SQL_PREFIXES = {"SELECT", "EXPLAIN"};

    /** 密码引用格式：整串形如 ${ENV_VAR_NAME} */
    public static final Pattern PASSWORD_REF_PATTERN =
            Pattern.compile("^\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}$");

    /** 内嵌账密连接串特征：jdbc:xxx://user:pass@host */
    public static final Pattern EMBEDDED_CREDENTIAL_URL_PATTERN =
            Pattern.compile("^jdbc:[^/\\s]*://[^/\\s@]*@");

    /** JDBC URL 前缀校验 */
    public static final String JDBC_URL_PREFIX = "jdbc:";
}
