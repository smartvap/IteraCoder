package com.agenthub.ai.dbaccess.validation;

import com.agenthub.ai.dbaccess.model.DbType;

/**
 * OceanBase 只读账号 {@code 用户@租户} 格式校验器（FR-BE-010/011，data-model §3.4）。
 *
 * <p>适用范围：仅 {@code dbType ∈ {OCEANBASE_MYSQL, OCEANBASE_ORACLE}} 生效；
 * {@code MYSQL / ORACLE} 恒返回「通过」（零影响，SC-003）。</p>
 *
 * <p>合法形态：{@code 用户@租户} 或 {@code 用户@租户#集群}（{@code @} 前非空、{@code @} 后非空）；
 * 非法形态：不含 {@code @}、{@code @} 前/后为空（{@code #集群} 前租户段为空亦非法）。</p>
 *
 * <p>副作用：无（不落库、不改 Entity）。对 {@code null}/空白 {@code readonlyUser}、{@code null} {@code dbType}
 * 均不抛异常（NFR-BE-006）。</p>
 */
public final class OceanBaseAccountValidator {

    /** OceanBase 只读账号格式提示（定稿文案，调用方以 40000 抛出，message 不含账号值） */
    public static final String TIP = "OceanBase 只读账号需形如 用户名@租户（租户名需与目标租户一致）";

    private OceanBaseAccountValidator() {
    }

    /**
     * 该库类型是否要求只读账号携带 {@code @租户}。
     *
     * @param dbTypeCode 库类型字符串（可空，大小写/分隔符容忍）
     * @return 仅 {@code OCEANBASE_MYSQL / OCEANBASE_ORACLE} 返回 {@code true}；其余（含 {@code null}/无法识别）返回 {@code false}
     */
    public static boolean requiresTenant(String dbTypeCode) {
        DbType dbType = DbType.parse(dbTypeCode);
        return dbType == DbType.OCEANBASE_MYSQL || dbType == DbType.OCEANBASE_ORACLE;
    }

    /**
     * 只读账号是否通过格式校验。
     *
     * @param dbTypeCode 库类型字符串（可空）
     * @param readonlyUser 只读账号（可空/空白）
     * @return 非 OB 族恒 {@code true}；OB 族要求 {@code @} 前非空且 {@code @} 后（去掉可选 {@code #集群}）非空
     */
    public static boolean isValid(String dbTypeCode, String readonlyUser) {
        if (!requiresTenant(dbTypeCode)) {
            return true;
        }
        if (readonlyUser == null || readonlyUser.isBlank()) {
            return false;
        }
        String user = readonlyUser.trim();
        int at = user.indexOf('@');
        if (at <= 0) {
            // 不含 @ 或 @ 前为空（如 "@租户"）均非法
            return false;
        }
        String tenantPart = user.substring(at + 1);
        // 允许 用户@租户#集群：取 # 之前的租户段判定非空
        int hash = tenantPart.indexOf('#');
        String tenant = hash >= 0 ? tenantPart.substring(0, hash) : tenantPart;
        return !tenant.isBlank();
    }
}
