package com.agenthub.ai.datasource.validation;

import com.agenthub.ai.dbaccess.model.DbType;

import java.util.List;

/**
 * 连接串末尾默认库 与 可访问库集合（{@code spaceNames}）一致性判定器（FR-BE-015，data-model §3.5）。
 *
 * <p>无状态纯函数：{@code check(dbTypeCode, jdbcUrl, spaceNames)} 提取 URL 末尾默认库并与 {@code spaceNames} 比对，
 * 返回 {@link Result}（一致 / 不一致 / 不适用）。<b>提示级别为 warning（非阻断）</b>；服务端<b>不阻断保存、
 * 不产出提示、不回显库名</b>（提示文案仅供前端基于用户自身输入展示，Q5）。</p>
 *
 * <p>不适用（不判定）：{@code spaceNames} 为 {@code null}/空（既有「不声明库集合」语义）；URL 无默认库段
 * （含末尾为 {@code /}）；{@code dbType} 无法解析；Oracle 族（{@code OCEANBASE_ORACLE / ORACLE}，Q4 默认不启用，
 * 避免与 schema/Owner 口径混淆）。解析异常一律静默返回不适用，<b>不抛异常</b>（BP-07/BP-08）。</p>
 */
public final class JdbcUrlSchemaConsistencyChecker {

    private JdbcUrlSchemaConsistencyChecker() {
    }

    /** 一致性判定结果 */
    public enum Result {
        /** 不一致：默认库不在可访问库集合中（warning，非阻断） */
        INCONSISTENT,
        /** 一致：默认库在可访问库集合中 */
        CONSISTENT,
        /** 不适用：未声明库集合 / 无默认库段 / 库类型无法解析 / Oracle 族 / 解析失败 */
        NOT_APPLICABLE
    }

    /**
     * 判定 URL 默认库是否落在可访问库集合内。
     *
     * @param dbTypeCode 库类型字符串（可空）
     * @param jdbcUrl    JDBC 连接串（可空）
     * @param spaceNames 可访问库集合（可空/空 → 不适用）
     * @return 判定结果（永不为 null）
     */
    public static Result check(String dbTypeCode, String jdbcUrl, List<String> spaceNames) {
        if (spaceNames == null || spaceNames.isEmpty()) {
            return Result.NOT_APPLICABLE;
        }
        DbType dbType = DbType.parse(dbTypeCode);
        if (dbType == null) {
            return Result.NOT_APPLICABLE;
        }
        // Oracle/OceanBase-Oracle：URL 末尾通常为 service name/SID，与 schema/Owner 不同源，默认不适用
        if (dbType.isOracleFamily()) {
            return Result.NOT_APPLICABLE;
        }
        String defaultSchema = extractDefaultSchema(jdbcUrl);
        if (defaultSchema == null) {
            return Result.NOT_APPLICABLE;
        }
        for (String raw : spaceNames) {
            if (raw != null && defaultSchema.equals(raw.trim())) {
                return Result.CONSISTENT;
            }
        }
        return Result.INCONSISTENT;
    }

    /**
     * 提取连接串末尾默认库（默认 database/schema）。
     *
     * <p>步骤：先剥离 {@code ?} 之后的参数（避免 {@code ?socket=/tmp/x} 误判）；再取 {@code ://} 之后部分中
     * 最后一个 {@code /} 之后的非空段；无 {@code ://}（非标准 JDBC URL）、无路径段或末尾为 {@code /}
     * 均视为无默认库。解析异常静默返回 {@code null}。</p>
     *
     * @param jdbcUrl JDBC 连接串（可空）
     * @return 默认库名；无默认库或解析失败返回 {@code null}
     */
    public static String extractDefaultSchema(String jdbcUrl) {
        try {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                return null;
            }
            String url = jdbcUrl.trim();
            int queryIdx = url.indexOf('?');
            if (queryIdx >= 0) {
                url = url.substring(0, queryIdx);
            }
            int authorityIdx = url.indexOf("://");
            if (authorityIdx < 0) {
                return null;
            }
            String afterAuthority = url.substring(authorityIdx + "://".length());
            int slash = afterAuthority.lastIndexOf('/');
            if (slash < 0) {
                // 无路径段（如 jdbc:mysql://h:3306）
                return null;
            }
            String segment = afterAuthority.substring(slash + 1).trim();
            return segment.isEmpty() ? null : segment;
        } catch (Exception e) {
            // 解析异常静默不提示，不抛异常
            return null;
        }
    }

    /**
     * 生成前端提示文案（定稿，含用户自身输入的默认库名；服务端不调用、不落日志/响应）。
     *
     * @param defaultSchema 默认库名（用户输入）
     * @return 形如「连接串默认库 &lt;库名&gt; 不在可访问库集合中，可能导致连接失败或字典权限不足，请确认」
     */
    public static String suggestion(String defaultSchema) {
        return "连接串默认库 " + defaultSchema + " 不在可访问库集合中，可能导致连接失败或字典权限不足，请确认";
    }
}
