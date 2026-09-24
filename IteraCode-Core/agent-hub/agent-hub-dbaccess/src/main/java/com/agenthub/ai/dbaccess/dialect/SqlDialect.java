package com.agenthub.ai.dbaccess.dialect;

import com.agenthub.ai.dbaccess.model.DbType;

import java.util.List;

/**
 * SQL 方言 SPI（data-model.md t_dialect_rule 驱动）。
 *
 * <p>生成侧（rca/engine 调）、验证侧（module-006 调）、执行侧（本模块执行）统一经 {@link DialectManager} 获取。</p>
 */
public interface SqlDialect {

    /** 适用库类型 */
    DbType dbType();

    /**
     * 分页包装 SQL。
     *
     * <p>防注入：分页为固定模板拼装 + offset/limit 整型校验后拼接，禁止字符串注入直达执行。</p>
     *
     * @param sql    原 SELECT SQL
     * @param offset 起始行（≥0）
     * @param limit  页大小（&gt;0）
     * @return 包装后的分页 SQL
     */
    String buildPagedSql(String sql, int offset, int limit);

    /** 当前时间表达式 */
    String currentTimestampExpression();

    /** 字符串拼接表达式（多段） */
    String concatExpression(List<String> parts);

    /**
     * 将排序子句改写为 NULL 值置后。
     *
     * @param orderByClause 原始排序列表达式（不含 ASC/DESC 时的语义由 asc 决定）
     * @param asc           是否升序
     * @return 可直接追加到 ORDER BY 后的表达式
     */
    String nullsLast(String orderByClause, boolean asc);

    /**
     * 统一函数名映射（如 LENGTH/SUBSTR 等）。入参为函数名（不含括号），未知原样返回。
     */
    String function(String name);

    /** 连接池健康检查/校验 SQL */
    default String validationQuery() {
        return "SELECT 1";
    }
}
