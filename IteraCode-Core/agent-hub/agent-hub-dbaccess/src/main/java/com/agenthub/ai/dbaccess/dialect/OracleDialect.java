package com.agenthub.ai.dbaccess.dialect;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;

import java.util.List;
import java.util.Locale;

/**
 * Oracle 方言实现（OCEANBASE_ORACLE 归口同一实现）。
 *
 * <p>四库兼容增强：分页默认采用 Oracle 12c+ / OceanBase 4.x 标准 {@code OFFSET n ROWS FETCH NEXT m ROWS ONLY}
 * 语法；对不支持该语法的旧版本（Oracle 11g 等）保留 ROWNUM 三层包装兜底
 * （{@link #buildRownumPagedSql}）；函数映射补齐 NVL/SUBSTR/SYSDATE。</p>
 */
public class OracleDialect implements SqlDialect {

    /** OceanBase-Oracle 兼容模式默认上报 ORACLE 方言标识 */
    @Override
    public DbType dbType() {
        return DbType.ORACLE;
    }

    @Override
    public String buildPagedSql(String sql, int offset, int limit) {
        if (sql == null || sql.isBlank()) {
            throw new DbAccessException(DbAccessErrorCode.PARAM_INVALID, "分页 SQL 不能为空");
        }
        if (offset < 0 || limit <= 0) {
            throw new DbAccessException(DbAccessErrorCode.PARAM_INVALID, "分页 offset/limit 非法");
        }
        // 优先标准 OFFSET-FETCH（Oracle 12c+ / OB 4.x）；offset/limit 已整型校验，无注入面。
        // 不支持 FETCH 的旧版本由调用方改用 buildRownumPagedSql 兜底。
        return sql + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY";
    }

    /**
     * 兜底分页：ROWNUM 三层包装（Oracle 11g 及以下 / 不支持 OFFSET-FETCH 的兼容模式）。
     *
     * <p>内层先排序，外层以 ROWNUM 截断窗口下界，再取 rn_ &gt; offset 的窗口。offset/limit 已整型校验，无注入面。</p>
     */
    public String buildRownumPagedSql(String sql, int offset, int limit) {
        if (sql == null || sql.isBlank()) {
            throw new DbAccessException(DbAccessErrorCode.PARAM_INVALID, "分页 SQL 不能为空");
        }
        if (offset < 0 || limit <= 0) {
            throw new DbAccessException(DbAccessErrorCode.PARAM_INVALID, "分页 offset/limit 非法");
        }
        long end = (long) offset + limit;
        return "SELECT * FROM ( SELECT row_.*, ROWNUM rn_ FROM ( " + sql + " ) row_ WHERE ROWNUM <= "
                + end + " ) WHERE rn_ > " + offset;
    }

    @Override
    public String currentTimestampExpression() {
        return "SYSDATE";
    }

    @Override
    public String concatExpression(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new DbAccessException(DbAccessErrorCode.PARAM_INVALID, "拼接表达式段不能为空");
        }
        // Oracle 原生 || 拼接
        return String.join(" || ", parts);
    }

    @Override
    public String nullsLast(String orderByClause, boolean asc) {
        // Oracle 原生支持 NULLS LAST
        return orderByClause + (asc ? " ASC" : " DESC") + " NULLS LAST";
    }

    @Override
    public String function(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "LENGTH" -> "LENGTH";
            case "SUBSTR", "SUBSTRING" -> "SUBSTR";
            case "NVL", "IFNULL" -> "NVL";
            case "SYSDATE", "NOW" -> "SYSDATE";
            case "CONCAT" -> "CONCAT";
            case "TO_DATE" -> "TO_DATE";
            default -> name.trim();
        };
    }

    @Override
    public String validationQuery() {
        return "SELECT 1 FROM DUAL";
    }
}
