package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 涉及列引用（SqlFeature.columns）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnRef {

    /** 所属表的库/Schema 限定（可空；跨库时如 DB_A） */
    private String schema;

    /** 所属表（可空：无法归属或来自子查询；为纯表名/别名，不含 schema） */
    private String table;

    /** 列名（可能带别名场景下的原始标识） */
    private String column;

    /** 是否为 SELECT 投影项 */
    private Boolean isSelectItem;

    /** 是否出现在 WHERE 中 */
    private Boolean isWhere;

    /** 是否出现在 JOIN ON 中 */
    private Boolean isJoin;

    /** 是否聚合函数参数/结果列 */
    private Boolean isAggregate;

    /** 是否出现在 GROUP BY 中（MySQL 允许该子句引用 SELECT 输出别名） */
    private Boolean isGroupBy;

    /** 是否出现在 HAVING 中（MySQL 允许该子句引用 SELECT 输出别名） */
    private Boolean isHaving;

    /** 是否出现在 ORDER BY 中（MySQL 允许该子句引用 SELECT 输出别名） */
    private Boolean isOrderBy;

    public boolean selectItem() {
        return Boolean.TRUE.equals(isSelectItem);
    }

    public boolean where() {
        return Boolean.TRUE.equals(isWhere);
    }

    public boolean join() {
        return Boolean.TRUE.equals(isJoin);
    }

    public boolean aggregate() {
        return Boolean.TRUE.equals(isAggregate);
    }

    public boolean groupBy() {
        return Boolean.TRUE.equals(isGroupBy);
    }

    public boolean having() {
        return Boolean.TRUE.equals(isHaving);
    }

    public boolean orderBy() {
        return Boolean.TRUE.equals(isOrderBy);
    }

    /**
     * 是否来自「可合法引用 SELECT 输出别名」的子句（GROUP BY / HAVING / ORDER BY）。
     *
     * <p>MySQL 仅允许排序/分组/聚合过滤子句引用输出别名；WHERE / JOIN ON / SELECT 列表引用别名属非法
     * （执行期 unknown column）。L2 未限定列校验据此区分，避免 WHERE 引用别名被误放行（H10 同类漏网）。</p>
     */
    public boolean aliasExemptClause() {
        return groupBy() || having() || orderBy();
    }
}
