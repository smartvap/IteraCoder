package com.agenthub.ai.dbaccess.dialect;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySqlDialect 分页（LIMIT）与表达式测试。
 */
class MySqlDialectTest {

    private final MySqlDialect dialect = new MySqlDialect();

    @Test
    @DisplayName("MySQL 分页生成 LIMIT offset, rows")
    void should_appendLimit_when_paging() {
        assertThat(dialect.buildPagedSql("SELECT * FROM t", 0, 20))
                .isEqualTo("SELECT * FROM t LIMIT 0, 20");
        assertThat(dialect.buildPagedSql("SELECT id FROM t WHERE x = 1", 10, 100))
                .isEqualTo("SELECT id FROM t WHERE x = 1 LIMIT 10, 100");
    }

    @Test
    @DisplayName("分页参数非法时抛 PARAM_INVALID")
    void should_throw_when_offsetOrLimitInvalid() {
        assertThatThrownBy(() -> dialect.buildPagedSql("SELECT 1", -1, 10))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
        assertThatThrownBy(() -> dialect.buildPagedSql("SELECT 1", 0, 0))
                .isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> dialect.buildPagedSql("  ", 0, 10))
                .isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> dialect.buildPagedSql(null, 0, 10))
                .isInstanceOf(DbAccessException.class);
    }

    @Test
    @DisplayName("当前时间表达式 NOW()")
    void should_returnNow_when_currentTimestamp() {
        assertThat(dialect.currentTimestampExpression()).isEqualTo("NOW()");
    }

    @Test
    @DisplayName("字符串拼接生成 CONCAT(...)")
    void should_concat_when_parts() {
        assertThat(dialect.concatExpression(List.of("a", "b", "'-'"))).isEqualTo("CONCAT(a, b, '-')");
        assertThatThrownBy(() -> dialect.concatExpression(List.of()))
                .isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> dialect.concatExpression(null))
                .isInstanceOf(DbAccessException.class);
    }

    @Test
    @DisplayName("nullsLast 升序/降序采用 IS NULL 布尔排序")
    void should_nullsLast_when_ascOrDesc() {
        assertThat(dialect.nullsLast("create_time", true))
                .isEqualTo("(create_time) IS NULL ASC, create_time ASC");
        assertThat(dialect.nullsLast("create_time", false))
                .isEqualTo("(create_time) IS NULL DESC, create_time DESC");
    }

    @Test
    @DisplayName("函数映射 LENGTH→CHAR_LENGTH、SUBSTR→SUBSTRING")
    void should_mapFunction_when_known() {
        assertThat(dialect.function("LENGTH")).isEqualTo("CHAR_LENGTH");
        assertThat(dialect.function("substr")).isEqualTo("SUBSTRING");
        assertThat(dialect.function("SUBSTRING")).isEqualTo("SUBSTRING");
        assertThat(dialect.function("NOW")).isEqualTo("NOW");
        assertThat(dialect.function("IFNULL")).isEqualTo("IFNULL");
        assertThat(dialect.function("DATE_FORMAT")).isEqualTo("DATE_FORMAT");
        // 未知函数原样返回（trim 后）
        assertThat(dialect.function("MY_FUNC")).isEqualTo("MY_FUNC");
        assertThat(dialect.function(null)).isNull();
    }

    @Test
    @DisplayName("dbType 与 validationQuery")
    void should_reportDbType_when_ask() {
        assertThat(dialect.dbType()).isEqualTo(DbType.MYSQL);
        assertThat(dialect.validationQuery()).isEqualTo("SELECT 1");
    }
}
