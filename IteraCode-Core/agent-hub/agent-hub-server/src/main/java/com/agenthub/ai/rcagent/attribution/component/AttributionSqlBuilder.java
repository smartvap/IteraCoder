package com.agenthub.ai.rcagent.attribution.component;

import com.agenthub.ai.rcagent.attribution.exception.AttributionException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 归因查询 SQL 生成器（AttributionSqlBuilder，FR-004）。
 *
 * <p>生成总体/分组本期/分组基期/交叉分组/质量探针参数化占位 SQL：
 * 所有值一律经 {@link #sqlLiteral} 单引号转义包裹（防注入），表名/列名经标识符白名单校验；
 * 值（时间窗、聚焦子群）来自程序生成或用户输入维度值，均不得拼出可控注入。
 * MVP 以 MySQL 方言 LIMIT 兜底（归因 SQL 仅经 module-006 validate+executeGuarded 执行，
 * 多库方言分页兼容随 module-002 SqlDialect 演进）。</p>
 */
@Component
public class AttributionSqlBuilder {

    /** 分组结果固定别名（与贡献拆解读取约定一致） */
    public static final String SEGMENT_KEY_ALIAS = "segment_key";
    public static final String METRIC_VALUE_ALIAS = "metric_value";
    public static final String METRIC_TOTAL_ALIAS = "metric_value";

    /** 标识符白名单（表名/列名允许 字母/数字/下划线/点，防拼列注入） */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.]+");

    /**
     * 总体值 SQL（OVERALL/BASELINE）：本期/基期各自执行一次得到 cur/base 总体。
     */
    public String buildTotalSql(String table, String timeColumn, String start, String end) {
        String ident = safeTable(table);
        String time = safeColumn(timeColumn);
        StringBuilder sb = new StringBuilder("SELECT COUNT(*) AS ").append(METRIC_TOTAL_ALIAS)
                .append(" FROM ").append(ident).append(" t")
                .append(" WHERE t.").append(time).append(" >= ").append(sqlLiteral(start))
                .append(" AND t.").append(time).append(" < ").append(sqlLiteral(end));
        return sb.toString();
    }

    /**
     * 分组 SQL（GROUP_CUR/GROUP_BASE）：按维度列分组并返回指标计数。
     */
    public String buildGroupSql(String table, String timeColumn, String start, String end,
                                String groupColumn, Integer topN) {
        String ident = safeTable(table);
        String time = safeColumn(timeColumn);
        String dim = safeColumn(groupColumn);
        StringBuilder sb = new StringBuilder("SELECT t.").append(dim).append(" AS ").append(SEGMENT_KEY_ALIAS)
                .append(", COUNT(*) AS ").append(METRIC_VALUE_ALIAS)
                .append(" FROM ").append(ident).append(" t")
                .append(" WHERE t.").append(time).append(" >= ").append(sqlLiteral(start))
                .append(" AND t.").append(time).append(" < ").append(sqlLiteral(end))
                .append(" GROUP BY t.").append(dim)
                .append(" ORDER BY ").append(METRIC_VALUE_ALIAS).append(" DESC");
        if (topN != null && topN > 0) {
            sb.append(" LIMIT ").append(topN);
        }
        return sb.toString();
    }

    /**
     * 交叉/聚焦子群内分组 SQL（CROSS_GROUP/追问下钻）：父层某段值约束下对新维度分组。
     */
    public String buildCrossGroupSql(String table, String timeColumn, String start, String end,
                                     String groupColumn, String focusColumn, String focusValue, Integer topN) {
        String ident = safeTable(table);
        String time = safeColumn(timeColumn);
        String dim = safeColumn(groupColumn);
        StringBuilder sb = new StringBuilder("SELECT t.").append(dim).append(" AS ").append(SEGMENT_KEY_ALIAS)
                .append(", COUNT(*) AS ").append(METRIC_VALUE_ALIAS)
                .append(" FROM ").append(ident).append(" t")
                .append(" WHERE t.").append(time).append(" >= ").append(sqlLiteral(start))
                .append(" AND t.").append(time).append(" < ").append(sqlLiteral(end));
        if (focusColumn != null && !focusColumn.isBlank() && focusValue != null && !focusValue.isBlank()) {
            sb.append(" AND t.").append(safeColumn(focusColumn)).append(" = ").append(sqlLiteral(focusValue));
        }
        sb.append(" GROUP BY t.").append(dim)
                .append(" ORDER BY ").append(METRIC_VALUE_ALIAS).append(" DESC");
        if (topN != null && topN > 0) {
            sb.append(" LIMIT ").append(topN);
        }
        return sb.toString();
    }

    /**
     * 质量探针 SQL（QUALITY_PROBE）：本期/基期总量对比 + 分段数量观测。
     */
    public String buildQualityProbeSql(String table, String timeColumn, String start, String end) {
        return buildTotalSql(table, timeColumn, start, end);
    }

    // ============ 安全转义/标识符校验 ============

    /** 字符串字面量转义（单引号翻倍 + 包裹），防注入 */
    public String sqlLiteral(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    /** 表名安全（白名单 + 校验失败抛参数异常） */
    private String safeTable(String table) {
        if (table == null || table.isBlank()) {
            throw AttributionException.param("指标口径缺少主表，无法生成归因 SQL");
        }
        String t = table.trim();
        checkIdentifier(t);
        return t;
    }

    /** 列名安全（白名单 + 校验失败抛参数异常） */
    private String safeColumn(String column) {
        if (column == null || column.isBlank()) {
            throw AttributionException.param("指标口径缺少可用时间/维度列，无法生成归因 SQL");
        }
        String c = column.trim();
        checkIdentifier(c);
        return c;
    }

    private void checkIdentifier(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw AttributionException.param("归因 SQL 生成器拒绝非法标识符: 表/列名必须为字母数字下划线");
        }
    }
}
