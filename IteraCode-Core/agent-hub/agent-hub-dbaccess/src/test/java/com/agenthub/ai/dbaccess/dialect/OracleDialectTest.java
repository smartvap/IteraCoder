package com.agenthub.ai.dbaccess.dialect;

import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OracleDialect 测试（四库兼容增强后：默认 OFFSET-FETCH 分页 + ROWNUM 三层包装兜底）。
 *
 * <p>行为变更说明（FR-BE-007 / spec 场景 10）：{@code buildPagedSql} 优先采用
 * Oracle 12c+ / OceanBase 4.x 标准 {@code OFFSET n ROWS FETCH NEXT m ROWS ONLY}；
 * 旧版本（Oracle 11g 等）由 {@code buildRownumPagedSql} 提供 ROWNUM 兜底。
 * 原“分页即 ROWNUM”的过期断言已按新行为更新，并为 ROWNUM 兜底方法补齐覆盖。</p>
 */
class OracleDialectTest {

    private final OracleDialect dialect = new OracleDialect();

    // ------------------------------------------------------------------
    // buildPagedSql：默认 OFFSET-FETCH（12c+ / OB 4.x）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Oracle 分页优先 OFFSET n ROWS FETCH NEXT m ROWS ONLY（12c+ / OB 4.x）")
    void should_buildOffsetFetch_when_paging() {
        String sql = dialect.buildPagedSql("SELECT id FROM t ORDER BY id", 10, 20);
        assertThat(sql)
                .isEqualTo("SELECT id FROM t ORDER BY id OFFSET 10 ROWS FETCH NEXT 20 ROWS ONLY");
    }

    @Test
    @DisplayName("Oracle 分页 offset=0 亦生成标准 OFFSET-FETCH")
    void should_buildOffsetFetch_when_offsetZero() {
        String sql = dialect.buildPagedSql("SELECT id FROM t", 0, 5);
        assertThat(sql).isEqualTo("SELECT id FROM t OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY");
    }

    // ------------------------------------------------------------------
    // buildRownumPagedSql：ROWNUM 三层包装兜底（旧版本回退）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Oracle ROWNUM 兜底分页：ROWNUM 三层包装，窗口下界 rn_ > offset")
    void should_buildRownumFallback_when_legacyPaging() {
        String sql = dialect.buildRownumPagedSql("SELECT id FROM t ORDER BY id", 10, 20);
        assertThat(sql)
                .startsWith("SELECT * FROM ( SELECT row_.*, ROWNUM rn_ FROM ( SELECT id FROM t ORDER BY id ) row_")
                .contains("WHERE ROWNUM <= 30")
                .endsWith("WHERE rn_ > 10");
    }

    @Test
    @DisplayName("Oracle ROWNUM 兜底分页 offset=0：上界 = limit，下界 rn_ > 0")
    void should_buildRownumFallback_when_offsetZero() {
        String sql = dialect.buildRownumPagedSql("SELECT id FROM t", 0, 5);
        assertThat(sql)
                .contains("WHERE ROWNUM <= 5")
                .endsWith("WHERE rn_ > 0");
    }

    // ------------------------------------------------------------------
    // 非法参数：默认分页与 ROWNUM 兜底两路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Oracle 非法分页参数抛 PARAM_INVALID（buildPagedSql）")
    void should_throw_when_pagingInvalid() {
        assertThatThrownBy(() -> dialect.buildPagedSql("SELECT 1", -1, 10))
                .isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> dialect.buildPagedSql(null, 0, 10))
                .isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> dialect.buildPagedSql("SELECT 1", 0, 0))
                .isInstanceOf(DbAccessException.class);
    }

    @Test
    @DisplayName("Oracle 非法分页参数抛 PARAM_INVALID（buildRownumPagedSql 兜底）")
    void should_throw_when_rownumPagingInvalid() {
        assertThatThrownBy(() -> dialect.buildRownumPagedSql("SELECT 1", -1, 10))
                .isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> dialect.buildRownumPagedSql("   ", 0, 10))
                .isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> dialect.buildRownumPagedSql("SELECT 1", 0, -5))
                .isInstanceOf(DbAccessException.class);
    }

    // ------------------------------------------------------------------
    // 表达式与函数映射
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Oracle 表达式与函数映射（补齐 NVL/SUBSTR/SYSDATE）")
    void should_mapExpressions_when_ask() {
        assertThat(dialect.currentTimestampExpression()).isEqualTo("SYSDATE");
        assertThat(dialect.concatExpression(List.of("a", "b"))).isEqualTo("a || b");
        assertThat(dialect.nullsLast("create_time", false)).isEqualTo("create_time DESC NULLS LAST");
        assertThat(dialect.function("LENGTH")).isEqualTo("LENGTH");
        assertThat(dialect.function("IFNULL")).isEqualTo("NVL");
        assertThat(dialect.function("SUBSTRING")).isEqualTo("SUBSTR");
        assertThat(dialect.function("NOW")).isEqualTo("SYSDATE");
        assertThat(dialect.validationQuery()).isEqualTo("SELECT 1 FROM DUAL");
        assertThat(dialect.dbType()).isEqualTo(DbType.ORACLE);
    }
}
