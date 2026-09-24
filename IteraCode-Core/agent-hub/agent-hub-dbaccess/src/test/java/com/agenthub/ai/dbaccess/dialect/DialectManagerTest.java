package com.agenthub.ai.dbaccess.dialect;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DialectManager 方言路由与缓存测试。
 */
class DialectManagerTest {

    private final DialectManager manager = new DialectManager();

    @Test
    @DisplayName("MYSQL/OCEANBASE_MYSQL 路由 MySQL 方言，缓存命中返回同一实例")
    void should_routeMysql_when_mysqlFamily() {
        SqlDialect mysql = manager.dialectOf(DbType.MYSQL);
        assertThat(mysql).isInstanceOf(MySqlDialect.class);
        // OceanBase MySQL 兼容模式复用同一实例
        assertThat(manager.dialectOf(DbType.OCEANBASE_MYSQL)).isSameAs(mysql);
        // 缓存命中同一实例
        assertThat(manager.dialectOf(DbType.MYSQL)).isSameAs(mysql);
    }

    @Test
    @DisplayName("ORACLE/OCEANBASE_ORACLE 路由 Oracle 方言")
    void should_routeOracle_when_oracleFamily() {
        SqlDialect oracle = manager.dialectOf(DbType.ORACLE);
        assertThat(oracle).isInstanceOf(OracleDialect.class);
        assertThat(manager.dialectOf(DbType.OCEANBASE_ORACLE)).isSameAs(oracle);
    }

    @Test
    @DisplayName("null dbType 抛 DIALECT_UNSUPPORTED")
    void should_throw_when_dbTypeNull() {
        assertThatThrownBy(() -> manager.dialectOf(null))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.DIALECT_UNSUPPORTED.getCode()));
    }

    @Test
    @DisplayName("register 注册新方言后可按库类型解析")
    void should_register_when_newDialect() {
        SqlDialect custom = new MySqlDialect();
        manager.register(DbType.MYSQL, custom);
        assertThat(manager.dialectOf(DbType.MYSQL)).isSameAs(custom);
    }

    @Test
    @DisplayName("register 空参数抛 PARAM_INVALID")
    void should_throw_when_registerParamNull() {
        assertThatThrownBy(() -> manager.register(null, new MySqlDialect()))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
        assertThatThrownBy(() -> manager.register(DbType.MYSQL, null))
                .isInstanceOf(DbAccessException.class);
    }
}
