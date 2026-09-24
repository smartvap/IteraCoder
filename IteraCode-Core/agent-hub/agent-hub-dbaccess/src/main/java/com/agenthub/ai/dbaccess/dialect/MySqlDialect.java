package com.agenthub.ai.dbaccess.dialect;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;

import java.util.List;
import java.util.Locale;

/**
 * MySQL 8.x 方言实现（OCEANBASE_MYSQL 归口同一实现）。
 *
 * <p>分页风格：LIMIT offset, rows。</p>
 */
public class MySqlDialect implements SqlDialect {

    @Override
    public DbType dbType() {
        return DbType.MYSQL;
    }

    @Override
    public String buildPagedSql(String sql, int offset, int limit) {
        if (sql == null || sql.isBlank()) {
            throw new DbAccessException(DbAccessErrorCode.PARAM_INVALID, "分页 SQL 不能为空");
        }
        if (offset < 0 || limit <= 0) {
            throw new DbAccessException(DbAccessErrorCode.PARAM_INVALID, "分页 offset/limit 非法");
        }
        // offset/limit 为整型且已校验范围，此处为固定模板拼装，无注入面
        return sql + " LIMIT " + offset + ", " + limit;
    }

    @Override
    public String currentTimestampExpression() {
        return "NOW()";
    }

    @Override
    public String concatExpression(List<String> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new DbAccessException(DbAccessErrorCode.PARAM_INVALID, "拼接表达式段不能为空");
        }
        return "CONCAT(" + String.join(", ", parts) + ")";
    }

    @Override
    public String nullsLast(String orderByClause, boolean asc) {
        // MySQL 8 不支持标准 NULLS LAST 语法，采用 (expr) IS NULL 布尔排序技巧：
        // 升序时 NULL(=1) 置后：ORDER BY (expr) IS NULL, expr ASC
        // 降序时 NULL(=1) 需排最末：ORDER BY (expr) IS NULL DESC, expr DESC
        String base = "(" + orderByClause + ") IS NULL";
        return asc ? (base + " ASC, " + orderByClause + " ASC")
                : (base + " DESC, " + orderByClause + " DESC");
    }

    @Override
    public String function(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String upper = name.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "LENGTH" -> "CHAR_LENGTH";      // 按字符数而非字节
            case "SUBSTR", "SUBSTRING" -> "SUBSTRING";
            case "IFNULL" -> "IFNULL";
            case "NOW" -> "NOW";
            case "CONCAT" -> "CONCAT";
            case "DATE_FORMAT" -> "DATE_FORMAT";
            default -> name.trim();
        };
    }

    @Override
    public String validationQuery() {
        return "SELECT 1";
    }
}
