package com.agenthub.ai.dbaccess.model;

import com.agenthub.ai.dbaccess.constant.DbAccessConstants;

import java.util.Locale;

/**
 * 数据库类型枚举（与 data-model.md 数据字典 DbType 对齐）。
 *
 * <p>四库范围：MYSQL / OCEANBASE_MYSQL 归口 MySQL 方言族；ORACLE / OCEANBASE_ORACLE 归口 Oracle 方言族。
 * 驱动解析统一走 {@link #resolveDriverClass(String, String)}（三段式优先级：显式 &gt; URL 前缀 &gt; 库类型默认）。</p>
 */
public enum DbType {

    /** MySQL 8.x */
    MYSQL("MYSQL", "MySQL", "com.mysql.cj.jdbc.Driver", "jdbc:mysql:"),

    /** Oracle 11g/19c */
    ORACLE("ORACLE", "Oracle", "oracle.jdbc.OracleDriver", "jdbc:oracle:"),

    /** OceanBase（MySQL 兼容模式），归口 MySQL 方言与 Provider；默认按 OB 驱动，存量 jdbc:mysql: 仍走 mysql 驱动 */
    OCEANBASE_MYSQL("OCEANBASE_MYSQL", "OceanBase(MySQL)", "com.oceanbase.jdbc.Driver", "jdbc:oceanbase:"),

    /** OceanBase（Oracle 兼容模式），归口 Oracle 方言与 Provider */
    OCEANBASE_ORACLE("OCEANBASE_ORACLE", "OceanBase(Oracle)", "com.oceanbase.jdbc.Driver", "jdbc:oceanbase:");

    private final String code;
    private final String displayName;
    private final String defaultDriverClass;
    private final String jdbcUrlPrefix;

    DbType(String code, String displayName, String defaultDriverClass, String jdbcUrlPrefix) {
        this.code = code;
        this.displayName = displayName;
        this.defaultDriverClass = defaultDriverClass;
        this.jdbcUrlPrefix = jdbcUrlPrefix;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultDriverClass() {
        return defaultDriverClass;
    }

    public String getJdbcUrlPrefix() {
        return jdbcUrlPrefix;
    }

    /**
     * 驱动路由唯一入口（三段式优先级）。
     *
     * <p>优先级：<br>
     * ① {@code explicitDriverClass} 非空白 → 原样返回；<br>
     * ② 按 {@code jdbcUrl} 前缀推断：{@code jdbc:oceanbase:}→{@code com.oceanbase.jdbc.Driver}、
     * {@code jdbc:mysql:}→{@code com.mysql.cj.jdbc.Driver}、{@code jdbc:oracle:}→{@code oracle.jdbc.OracleDriver}；<br>
     * ③ 以上均不命中 → 回落本库类型的 {@link #defaultDriverClass}。</p>
     *
     * <p>兼容性关键：URL 前缀优先于库类型默认驱动，故存量以 {@code jdbc:mysql:} 注册的
     * OCEANBASE_MYSQL 数据源仍解析为 {@code com.mysql.cj.jdbc.Driver}（走 mysql-connector），行为不变。
     * 本方法为纯字符串判断（单次 &lt;1ms），本枚举实例非空时第三段必然返回非空驱动类名。</p>
     *
     * @param explicitDriverClass 显式配置的驱动类名（可空；含 {@code ${ENV}} 占位由调用方先解析）
     * @param jdbcUrl             JDBC 连接串（用于前缀推断，可空）
     * @return 解析后的驱动类名（显式原样返回，否则 URL 前缀推断，最后回落本库类型默认驱动）
     */
    public String resolveDriverClass(String explicitDriverClass, String jdbcUrl) {
        // ① 显式 driverClass 优先（原样返回，${ENV} 占位由调用方解析）
        if (explicitDriverClass != null && !explicitDriverClass.isBlank()) {
            return explicitDriverClass;
        }
        // ② 按 jdbcUrl 前缀推断（OceanBase 前缀优先于 mysql/oracle，避免误判）
        if (jdbcUrl != null) {
            String url = jdbcUrl.trim();
            if (url.startsWith("jdbc:oceanbase:")) {
                return "com.oceanbase.jdbc.Driver";
            }
            if (url.startsWith("jdbc:mysql:")) {
                return "com.mysql.cj.jdbc.Driver";
            }
            if (url.startsWith("jdbc:oracle:")) {
                return "oracle.jdbc.OracleDriver";
            }
        }
        // ③ 回落本库类型默认驱动
        return defaultDriverClass;
    }

    /** 是否属于 MySQL 方言族（MySQL / OceanBase-MySQL） */
    public boolean isMysqlFamily() {
        return this == MYSQL || this == OCEANBASE_MYSQL;
    }

    /** 是否属于 Oracle 方言族（Oracle / OceanBase-Oracle） */
    public boolean isOracleFamily() {
        return this == ORACLE || this == OCEANBASE_ORACLE;
    }

    /**
     * 大小写不敏感解析（容忍 yml / DB 存储的字符串值）。
     *
     * @param text 库类型字符串
     * @return 命中返回枚举，否则 {@code null}
     */
    public static DbType parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String upper = text.trim().toUpperCase(Locale.ROOT);
        // 兼容 oceanbase_mysql / oceanbase-mysql 两种分隔写法
        upper = upper.replace('-', '_');
        for (DbType type : values()) {
            if (type.code.equals(upper) || type.name().equals(upper)
                    || type.code.replace("_", "").equals(upper.replace("_", ""))) {
                return type;
            }
        }
        return null;
    }

    /** ds_id 格式校验（复用常量正则），非法抛参数异常由调用方自行抛出 */
    public static boolean isValidDsId(String dsId) {
        return dsId != null && dsId.matches(DbAccessConstants.DS_ID_PATTERN);
    }
}
