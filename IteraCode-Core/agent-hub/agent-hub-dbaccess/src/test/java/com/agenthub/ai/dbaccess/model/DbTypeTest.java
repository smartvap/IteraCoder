package com.agenthub.ai.dbaccess.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DbType 枚举解析与校验测试。
 */
class DbTypeTest {

    @ParameterizedTest
    @CsvSource({
            "mysql, MYSQL",
            "MYSQL, MYSQL",
            "MySQL, MYSQL",
            "oracle, ORACLE",
            "oceanbase_mysql, OCEANBASE_MYSQL",
            "oceanbase-mysql, OCEANBASE_MYSQL",
            "OCEANBASE_ORACLE, OCEANBASE_ORACLE",
            "oceanbase_oracle, OCEANBASE_ORACLE"
    })
    @DisplayName("大小写不敏感解析 db_type 字符串")
    void should_parseDbType_when_textMatches(String text, DbType expected) {
        assertThat(DbType.parse(text)).isEqualTo(expected);
    }

    @Test
    @DisplayName("未知/空白 db_type 解析返回 null")
    void should_returnNull_when_textUnknownOrBlank() {
        assertThat(DbType.parse("sqlserver")).isNull();
        assertThat(DbType.parse("")).isNull();
        assertThat(DbType.parse(null)).isNull();
        assertThat(DbType.parse("   ")).isNull();
    }

    @Test
    @DisplayName("MySQL 方言族与 Oracle 方言族判定正确")
    void should_identifyFamily_when_enumType() {
        assertThat(DbType.MYSQL.isMysqlFamily()).isTrue();
        assertThat(DbType.OCEANBASE_MYSQL.isMysqlFamily()).isTrue();
        assertThat(DbType.ORACLE.isOracleFamily()).isTrue();
        assertThat(DbType.OCEANBASE_ORACLE.isOracleFamily()).isTrue();
        assertThat(DbType.MYSQL.isOracleFamily()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({
            "crm-oracle-prod, true",
            "oa-mysql, true",
            "mysql_local_poc, true",
            "a, true",
            "Crm-Oracle, false",
            "-mysql, false",
            "ds_id_test, true",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, false",
            ", false"
    })
    @DisplayName("ds_id 格式校验（小写字母数字开头、≤64）")
    void should_validateDsId_when_givenPattern(String dsId, boolean expected) {
        assertThat(DbType.isValidDsId(dsId)).isEqualTo(expected);
    }

    @Test
    @DisplayName("defaultDriverClass 按库类型推断")
    void should_containDefaultDriver_when_enumType() {
        assertThat(DbType.MYSQL.getDefaultDriverClass()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(DbType.ORACLE.getDefaultDriverClass()).isEqualTo("oracle.jdbc.OracleDriver");
    }

    @Test
    @DisplayName("OceanBase 枚举默认驱动与 URL 前缀（四库兼容更新）")
    void should_exposeOceanBaseDefaults_when_enumType() {
        assertThat(DbType.OCEANBASE_MYSQL.getDefaultDriverClass()).isEqualTo("com.oceanbase.jdbc.Driver");
        assertThat(DbType.OCEANBASE_MYSQL.getJdbcUrlPrefix()).isEqualTo("jdbc:oceanbase:");
        assertThat(DbType.OCEANBASE_ORACLE.getDefaultDriverClass()).isEqualTo("com.oceanbase.jdbc.Driver");
        assertThat(DbType.OCEANBASE_ORACLE.getJdbcUrlPrefix()).isEqualTo("jdbc:oceanbase:");
        assertThat(DbType.MYSQL.getJdbcUrlPrefix()).isEqualTo("jdbc:mysql:");
        assertThat(DbType.ORACLE.getJdbcUrlPrefix()).isEqualTo("jdbc:oracle:");
    }

    // ------------------------------------------------------------------
    // resolveDriverClass：驱动路由唯一入口（三段式优先级：显式 > URL 前缀 > 库类型默认）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveDriverClass ①：显式 driverClass 非空白 → 原样优先返回")
    void should_returnExplicitDriver_when_explicitProvided() {
        // 显式驱动优先于任何 URL 前缀推断（即使 URL 指向其它库族）
        assertThat(DbType.MYSQL.resolveDriverClass("com.custom.Driver", "jdbc:oracle:thin:@h:1521/x"))
                .isEqualTo("com.custom.Driver");
        assertThat(DbType.OCEANBASE_ORACLE.resolveDriverClass("com.custom.Driver", "jdbc:mysql://h/db"))
                .isEqualTo("com.custom.Driver");
    }

    @Test
    @DisplayName("resolveDriverClass ①②：显式为 null/空白视为未配置 → 回落 URL 前缀")
    void should_fallbackToUrl_when_explicitBlank() {
        assertThat(DbType.OCEANBASE_MYSQL.resolveDriverClass(null, "jdbc:oceanbase://host:2881/db"))
                .isEqualTo("com.oceanbase.jdbc.Driver");
        assertThat(DbType.OCEANBASE_MYSQL.resolveDriverClass("   ", "jdbc:mysql://host:3306/db"))
                .isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @ParameterizedTest
    @CsvSource({
            "MYSQL, jdbc:mysql://host:3306/db, com.mysql.cj.jdbc.Driver",
            "MYSQL, jdbc:oceanbase://host:2881/db, com.oceanbase.jdbc.Driver",
            "ORACLE, jdbc:oracle:thin:@host:1521/ORCL, oracle.jdbc.OracleDriver",
            "OCEANBASE_MYSQL, jdbc:oceanbase://host:2881/db, com.oceanbase.jdbc.Driver",
            "OCEANBASE_ORACLE, jdbc:oceanbase://host:2881/db, com.oceanbase.jdbc.Driver"
    })
    @DisplayName("resolveDriverClass ②：无显式 → 按 jdbcUrl 前缀推断驱动")
    void should_inferDriverFromUrlPrefix_when_explicitAbsent(DbType dbType, String url, String expected) {
        assertThat(dbType.resolveDriverClass(null, url)).isEqualTo(expected);
    }

    @Test
    @DisplayName("resolveDriverClass 存量兼容：OCEANBASE_MYSQL + jdbc:mysql: → 仍走 mysql-connector 驱动")
    void should_keepMysqlDriver_when_oceanbaseWithMysqlUrl() {
        assertThat(DbType.OCEANBASE_MYSQL.resolveDriverClass(null, "jdbc:mysql://host:3306/db"))
                .isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    @DisplayName("resolveDriverClass ②：jdbcUrl 前后空白被 trim 后仍能前缀识别")
    void should_trimUrl_when_prefixMatch() {
        assertThat(DbType.MYSQL.resolveDriverClass(null, "  jdbc:oracle:thin:@h:1521/x  "))
                .isEqualTo("oracle.jdbc.OracleDriver");
    }

    @Test
    @DisplayName("resolveDriverClass ③：无显式且 URL 不可识别（含 null/空）→ 回落库类型默认驱动")
    void should_fallbackToDefault_when_noExplicitAndUnknownUrl() {
        assertThat(DbType.MYSQL.resolveDriverClass(null, null)).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(DbType.ORACLE.resolveDriverClass("  ", "jdbc:unknown://h/db"))
                .isEqualTo("oracle.jdbc.OracleDriver");
        assertThat(DbType.OCEANBASE_ORACLE.resolveDriverClass(null, "jdbc:postgresql://h/db"))
                .isEqualTo("com.oceanbase.jdbc.Driver");
    }
}
