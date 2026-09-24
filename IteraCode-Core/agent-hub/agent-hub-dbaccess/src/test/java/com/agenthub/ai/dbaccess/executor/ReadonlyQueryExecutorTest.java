package com.agenthub.ai.dbaccess.executor;

import com.agenthub.ai.dbaccess.config.DbAccessProperties;
import com.agenthub.ai.dbaccess.dialect.MySqlDialect;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.model.QueryRequest;
import com.agenthub.ai.dbaccess.model.QueryResult;
import com.agenthub.ai.dbaccess.model.ResourceLimits;
import com.agenthub.ai.dbaccess.model.SensitivePolicy;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.dbaccess.security.SensitiveFieldRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReadonlyQueryExecutor 只读执行核心链路测试（SQL 前置校验/白名单/敏感/参数化/限制截断）。
 *
 * <p>通过 Mockito 模拟 JDBC 链路（DataSource/Connection/PreparedStatement/ResultSet），
 * 验证纯逻辑前置校验与 PreparedStatement 参数化行为，不依赖真实 MySQL。</p>
 */
class ReadonlyQueryExecutorTest {

    private static final String DS_ID = "ds-test";

    private final DataSourceRegistry registry = mock(DataSourceRegistry.class);
    private final DbAccessProperties properties = new DbAccessProperties();
    private final SensitiveFieldRegistry sensitiveRegistry = new SensitiveFieldRegistry(properties);
    private final SqlReadonlyValidator validator = new SqlReadonlyValidator();
    private final ReadonlyQueryExecutor executor =
            new ReadonlyQueryExecutor(registry, sensitiveRegistry, validator, properties);

    // ------------------------------------------------------------------
    // helper
    // ------------------------------------------------------------------

    private void stubContext(WhitelistRule whitelist, ResourceLimits limits, DataSource ds) {
        DsConfigView view = DsConfigView.builder()
                .dsId(DS_ID)
                .dbType("MYSQL")
                .whitelist(whitelist)
                .build();
        DataSourceContext ctx = DataSourceContext.builder()
                .config(view)
                .dataSource(ds)
                .dialect(new MySqlDialect())
                .limits(limits)
                .build();
        when(registry.resolve(DS_ID)).thenReturn(ctx);
    }

    private void stubContextWithSpaces(WhitelistRule whitelist, List<String> spaceNames,
                                       ResourceLimits limits, DataSource ds) {
        DsConfigView view = DsConfigView.builder()
                .dsId(DS_ID)
                .dbType("MYSQL")
                .whitelist(whitelist)
                .spaceNames(spaceNames)
                .build();
        DataSourceContext ctx = DataSourceContext.builder()
                .config(view)
                .dataSource(ds)
                .dialect(new MySqlDialect())
                .limits(limits)
                .build();
        when(registry.resolve(DS_ID)).thenReturn(ctx);
    }

    private ResourceLimits dsLimits(int maxRows, int maxSeconds) {
        return ResourceLimits.builder()
                .maxRows(maxRows)
                .maxSeconds(maxSeconds)
                .sensitivePolicy(SensitivePolicy.DENY)
                .allowSelectStar(false)
                .build();
    }

    private WhitelistRule whitelistTables(String... tables) {
        return WhitelistRule.builder().tables(List.of(tables)).mode("ALLOW_ONLY").build();
    }

    private QueryRequest request(String sql) {
        return QueryRequest.builder().dsId(DS_ID).sql(sql).build();
    }

    /** 构建 1 列结果集 mock：依次返回 values 中的值，耗尽后 next=false */
    private ResultSet resultSet1Col(String label, Object... values) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn(label);
        when(rs.getMetaData()).thenReturn(meta);
        int[] row = {0};
        when(rs.next()).thenAnswer(inv -> {
            if (row[0] < values.length) {
                row[0]++;
                return true;
            }
            return false;
        });
        when(rs.getObject(anyInt())).thenAnswer(inv -> {
            if (row[0] <= 0) {
                throw new SQLException("getObject 应在 next 之后调用");
            }
            return values[row[0] - 1];
        });
        return rs;
    }

    // ------------------------------------------------------------------
    // 参数校验
    // ------------------------------------------------------------------

    @Test
    @DisplayName("null/空请求、非法 ds_id、空 SQL 抛 PARAM_INVALID")
    void should_reject_when_basicParamInvalid() {
        assertThatThrownBy(() -> executor.execute(null))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
        assertThatThrownBy(() -> executor.execute(QueryRequest.builder().dsId("").sql("SELECT 1").build()))
                .isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> executor.execute(QueryRequest.builder().dsId("BAD_ID").sql("SELECT 1").build()))
                .isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> executor.execute(QueryRequest.builder().dsId(DS_ID).sql("  ").build()))
                .isInstanceOf(DbAccessException.class);
    }

    @Test
    @DisplayName("request maxRows 超过模块默认上限（1000）抛 PARAM_INVALID")
    void should_reject_when_maxRowsExceedsModule() {
        QueryRequest req = request("SELECT 1");
        req.setMaxRows(1001);
        assertThatThrownBy(() -> executor.execute(req))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
    }

    @Test
    @DisplayName("request maxSeconds 超过模块默认上限（10）抛 PARAM_INVALID")
    void should_reject_when_maxSecondsExceedsModule() {
        QueryRequest req = request("SELECT 1");
        req.setMaxSeconds(11);
        assertThatThrownBy(() -> executor.execute(req))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
    }

    // ------------------------------------------------------------------
    // SQL 只读前置校验（拒绝 DML/DDL/多语句/SELECT * 策略）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DML/DDL（DELETE/UPDATE/INSERT/DROP/TRUNCATE/CREATE）抛 SQL_REJECTED")
    void should_reject_when_dmlOrDdl() {
        stubContext(whitelistTables("T"), dsLimits(1000, 10), null);
        List<String> badSqls = List.of(
                "DELETE FROM t",
                "UPDATE t SET a = 1",
                "INSERT INTO t VALUES (1)",
                "DROP TABLE t",
                "TRUNCATE TABLE t",
                "CREATE TABLE x (id int)"
        );
        for (String sql : badSqls) {
            assertThatThrownBy(() -> executor.execute(request(sql)))
                    .as("SQL should be rejected: %s", sql)
                    .isInstanceOf(DbAccessException.class)
                    .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                            .isEqualTo(DbAccessErrorCode.SQL_REJECTED.getCode()));
        }
    }

    @Test
    @DisplayName("多语句（内嵌分号）抛 SQL_REJECTED")
    void should_reject_when_multiStatement() {
        stubContext(whitelistTables("T"), dsLimits(1000, 10), null);
        assertThatThrownBy(() -> executor.execute(request("SELECT id FROM t; DROP TABLE t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.SQL_REJECTED.getCode()));
    }

    @Test
    @DisplayName("行锁子句 FOR UPDATE 抛 SQL_REJECTED")
    void should_reject_when_forUpdate() {
        stubContext(whitelistTables("T"), dsLimits(1000, 10), null);
        assertThatThrownBy(() -> executor.execute(request("SELECT id FROM t FOR UPDATE")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.SQL_REJECTED.getCode()));
    }

    @Test
    @DisplayName("allowSelectStar=false 时 SELECT * 抛 SQL_REJECTED")
    void should_reject_when_selectStarDisallowed() {
        stubContext(whitelistTables("T"), dsLimits(1000, 10), null);
        assertThatThrownBy(() -> executor.execute(request("SELECT * FROM t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.SQL_REJECTED.getCode()));
        assertThatThrownBy(() -> executor.execute(request("SELECT t.* FROM t")))
                .isInstanceOf(DbAccessException.class);
    }

    @Test
    @DisplayName("注释包裹 DML 去注释后仍被拦截")
    void should_reject_when_commentHidesDml() {
        stubContext(whitelistTables("T"), dsLimits(1000, 10), null);
        assertThatThrownBy(() -> executor.execute(request("/* hi */ DELETE FROM t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.SQL_REJECTED.getCode()));
    }

    // ------------------------------------------------------------------
    // 白名单 fail-closed
    // ------------------------------------------------------------------

    /** 构建成功返回 1 行 1 列结果的 JDBC mock 数据源 */
    private DataSource mockSuccessfulDs(String label, Object value) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        ResultSet rs = resultSet1Col(label, value);
        when(ps.getResultSet()).thenReturn(rs);
        return ds;
    }

    // ------------------------------------------------------------------
    // 白名单默认放行 + 显式模式
    // ------------------------------------------------------------------

    @Test
    @DisplayName("白名单 null 时默认放行全部表（默认允许语义）")
    void should_allow_when_whitelistNull() throws Exception {
        stubContext(null, dsLimits(1000, 10), mockSuccessfulDs("id", 1));

        QueryResult result = executor.execute(request("SELECT id FROM t"));

        assertThat(result.getRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("显式空白名单（ALLOW_ONLY + 空 tables）仍拒绝访问（保留 fail-closed）")
    void should_deny_when_explicitEmptyAllowOnly() {
        stubContext(WhitelistRule.empty(), dsLimits(1000, 10), null);
        assertThatThrownBy(() -> executor.execute(request("SELECT id FROM t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.WHITELIST_DENIED.getCode()));
    }

    @Test
    @DisplayName("DENY_ONLY 黑名单：命中黑名单表拒绝，未命中放行")
    void should_deny_when_denyOnlyHit() throws Exception {
        WhitelistRule wl = WhitelistRule.builder()
                .tables(List.of("SENSITIVE_LOG"))
                .mode(WhitelistRule.MODE_DENY_ONLY)
                .build();
        stubContext(wl, dsLimits(1000, 10), null);
        assertThatThrownBy(() -> executor.execute(request("SELECT id FROM sensitive_log")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.WHITELIST_DENIED.getCode()));

        // 未命中黑名单 → 放行并执行成功
        stubContext(wl, dsLimits(1000, 10), mockSuccessfulDs("id", 1));
        QueryResult result = executor.execute(request("SELECT id FROM customer"));
        assertThat(result.getRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ALLOW_ONLY 通配白名单：匹配表放行，未匹配拒绝")
    void should_allow_when_wildcardAllowOnly() throws Exception {
        WhitelistRule wl = WhitelistRule.builder()
                .tables(List.of("CRM_*"))
                .mode(WhitelistRule.MODE_ALLOW_ONLY)
                .build();
        stubContext(wl, dsLimits(1000, 10), mockSuccessfulDs("id", 1));
        QueryResult result = executor.execute(request("SELECT id FROM CRM_USER"));
        assertThat(result.getRowCount()).isEqualTo(1);

        stubContext(wl, dsLimits(1000, 10), null);
        assertThatThrownBy(() -> executor.execute(request("SELECT id FROM OA_ORDER")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.WHITELIST_DENIED.getCode()));
    }

    @Test
    @DisplayName("表不在白名单抛 WHITELIST_DENIED（fail-closed）")
    void should_deny_when_tableNotAllowed() {
        stubContext(whitelistTables("OTHER"), dsLimits(1000, 10), null);
        assertThatThrownBy(() -> executor.execute(request("SELECT id FROM t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.WHITELIST_DENIED.getCode()));
    }

    @Test
    @DisplayName("列级限制下查询未授权列抛 WHITELIST_DENIED")
    void should_deny_when_columnNotAllowed() throws Exception {
        WhitelistRule wl = WhitelistRule.builder()
                .tables(List.of("T"))
                .columns(Map.of("T", List.of("ID")))
                .mode("ALLOW_ONLY")
                .build();
        stubContext(wl, dsLimits(1000, 10), null);
        assertThatThrownBy(() -> executor.execute(request("SELECT name FROM t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.WHITELIST_DENIED.getCode()));
    }

    @Test
    @DisplayName("列级限制且投影无法解析（函数）时 fail-closed 拒绝")
    void should_deny_when_projectionUnparseableUnderColumnRestriction() throws Exception {
        WhitelistRule wl = WhitelistRule.builder()
                .tables(List.of("T"))
                .columns(Map.of("T", List.of("ID", "NAME")))
                .mode("ALLOW_ONLY")
                .build();
        stubContext(wl, dsLimits(1000, 10), null);
        assertThatThrownBy(() -> executor.execute(request("SELECT count(*) FROM t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.WHITELIST_DENIED.getCode()));
    }

    // ------------------------------------------------------------------
    // 库集合边界（spaceNames，数据源可访问库集合）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("库集合内跨库引用（DB_A.T1 JOIN DB_B.T2）放行（白名单 null）")
    void should_allow_when_crossSpaceInsideBoundary() throws Exception {
        stubContextWithSpaces(null, List.of("DB_A", "DB_B"), dsLimits(1000, 10), mockSuccessfulDs("id", 1));

        QueryResult result = executor.execute(request(
                "SELECT a.id FROM DB_A.T1 a JOIN DB_B.T2 b ON a.id = b.id"));

        assertThat(result.getRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("库集合内引用（DB_A.T1）放行")
    void should_allow_when_schemaInsideBoundary() throws Exception {
        stubContextWithSpaces(null, List.of("DB_A"), dsLimits(1000, 10), mockSuccessfulDs("id", 1));

        QueryResult result = executor.execute(request("SELECT id FROM DB_A.T1"));

        assertThat(result.getRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("库集合外引用（DB_C.T3）抛 WHITELIST_DENIED 越界拒绝")
    void should_deny_when_schemaOutsideBoundary() {
        stubContextWithSpaces(null, List.of("DB_A"), dsLimits(1000, 10), null);

        assertThatThrownBy(() -> executor.execute(request("SELECT id FROM DB_C.T3")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.WHITELIST_DENIED.getCode()));
    }

    @Test
    @DisplayName("未声明库集合（null/空）不设跨库边界：集合外引用按现状放行")
    void should_skipBoundary_when_spaceNamesAbsent() throws Exception {
        // null：旧单库行为，集合外前缀不拦截
        stubContext(null, dsLimits(1000, 10), mockSuccessfulDs("id", 1));
        assertThat(executor.execute(request("SELECT id FROM DB_C.T3")).getRowCount()).isEqualTo(1);

        // 空集合：同样不设边界
        stubContextWithSpaces(null, List.of(), dsLimits(1000, 10), mockSuccessfulDs("id", 1));
        assertThat(executor.execute(request("SELECT id FROM DB_C.T3")).getRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("无 schema 前缀 SQL 不受库集合边界影响")
    void should_skipBoundary_when_noSchemaPrefix() throws Exception {
        stubContextWithSpaces(null, List.of("DB_A"), dsLimits(1000, 10), mockSuccessfulDs("id", 1));

        QueryResult result = executor.execute(request("SELECT id FROM t"));

        assertThat(result.getRowCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 敏感字段策略
    // ------------------------------------------------------------------

    @Test
    @DisplayName("DENY 策略预检命中 HIGH 敏感字段抛 SENSITIVE_DENIED")
    void should_deny_when_highSensitiveHit() {
        stubContext(whitelistTables("T"), dsLimits(1000, 10), null);
        sensitiveRegistry.registerSensitive(DS_ID, "T", "PHONE", com.agenthub.ai.dbaccess.model.SensitiveLevel.HIGH);
        assertThatThrownBy(() -> executor.execute(request("SELECT phone FROM t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.SENSITIVE_DENIED.getCode()));
    }

    @Test
    @DisplayName("MASK 策略对命中的敏感列脱敏并标记 sensitiveMasked")
    void should_mask_when_policyMask() throws Exception {
        stubContext(whitelistTables("T"), dsLimits(1000, 10), null);
        sensitiveRegistry.registerSensitive(DS_ID, "T", "PHONE", com.agenthub.ai.dbaccess.model.SensitiveLevel.HIGH);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        ResultSet rs = resultSet1Col("phone", "13812348000");
        when(ps.getResultSet()).thenReturn(rs);
        // 用允许 selectStar 的数据源限制 + MASK 策略，避免 DENY
        DsConfigView view = DsConfigView.builder().dsId(DS_ID).dbType("MYSQL").whitelist(whitelistTables("T")).build();
        ResourceLimits limits = ResourceLimits.builder().maxRows(1000).maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.MASK).allowSelectStar(false).build();
        when(registry.resolve(DS_ID)).thenReturn(DataSourceContext.builder()
                .config(view).dataSource(ds).dialect(new MySqlDialect()).limits(limits).build());

        QueryRequest req = request("SELECT phone FROM t");
        QueryResult result = executor.execute(req);

        assertThat(result.getSensitiveMasked()).isTrue();
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).get("phone")).isEqualTo("138****8000");
    }

    @Test
    @DisplayName("MASK 策略下 SELECT phone AS p 别名不可绕过脱敏（真实投影列仍命中）")
    void should_mask_when_aliasHidesSensitiveColumn() throws Exception {
        stubContext(whitelistTables("T"), dsLimits(1000, 10), null);
        sensitiveRegistry.registerSensitive(DS_ID, "T", "PHONE", com.agenthub.ai.dbaccess.model.SensitiveLevel.HIGH);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        // 结果列别名 p，真实列为 PHONE
        ResultSet rs = resultSet1Col("p", "13812348000");
        when(ps.getResultSet()).thenReturn(rs);
        DsConfigView view = DsConfigView.builder().dsId(DS_ID).dbType("MYSQL").whitelist(whitelistTables("T")).build();
        ResourceLimits limits = ResourceLimits.builder().maxRows(1000).maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.MASK).allowSelectStar(false).build();
        when(registry.resolve(DS_ID)).thenReturn(DataSourceContext.builder()
                .config(view).dataSource(ds).dialect(new MySqlDialect()).limits(limits).build());

        QueryResult result = executor.execute(request("SELECT phone AS p FROM t"));

        assertThat(result.getSensitiveMasked()).isTrue();
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).get("p")).isEqualTo("138****8000");
    }

    @Test
    @DisplayName("MASK 策略下子查询别名重命名不可绕过脱敏（SELECT p FROM (SELECT phone AS p ...)）")
    void should_mask_when_derivedTableAliasHidesSensitiveColumn() throws Exception {
        stubContext(whitelistTables("CUSTOMER"), dsLimits(1000, 10), null);
        sensitiveRegistry.registerSensitive(DS_ID, "CUSTOMER", "PHONE",
                com.agenthub.ai.dbaccess.model.SensitiveLevel.HIGH);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        // 结果列 label 为外层引用名 p，真实数据来自内层 phone（派生表别名重命名）
        ResultSet rs = resultSet1Col("p", "13812348000");
        when(ps.getResultSet()).thenReturn(rs);
        DsConfigView view = DsConfigView.builder().dsId(DS_ID).dbType("MYSQL")
                .whitelist(whitelistTables("CUSTOMER")).build();
        ResourceLimits limits = ResourceLimits.builder().maxRows(1000).maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.MASK).allowSelectStar(false).build();
        when(registry.resolve(DS_ID)).thenReturn(DataSourceContext.builder()
                .config(view).dataSource(ds).dialect(new MySqlDialect()).limits(limits).build());

        QueryResult result = executor.execute(request("SELECT p FROM (SELECT phone AS p FROM customer) t"));

        assertThat(result.getSensitiveMasked()).isTrue();
        assertThat(result.getRows()).hasSize(1);
        // 保守兜底：无法可信解析来源且 SQL 命中 PHONE → 整列脱敏，禁止明文回退
        assertThat(result.getRows().get(0).get("p")).isEqualTo("138****8000");
    }

    @Test
    @DisplayName("DENY 策略下子查询别名重命名敏感字段同样被拒（文本命中预检）")
    void should_deny_when_derivedTableAliasHidesSensitiveColumn() {
        stubContext(whitelistTables("CUSTOMER"), dsLimits(1000, 10), null);
        sensitiveRegistry.registerSensitive(DS_ID, "CUSTOMER", "PHONE",
                com.agenthub.ai.dbaccess.model.SensitiveLevel.HIGH);
        assertThatThrownBy(() -> executor.execute(
                request("SELECT p FROM (SELECT phone AS p FROM customer) t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.SENSITIVE_DENIED.getCode()));
    }

    @Test
    @DisplayName("MASK 策略下逗号连接多表派生表别名重命名不可绕过脱敏")
    void should_mask_when_commaDerivedTableAliasHidesSensitiveColumn() throws Exception {
        stubContext(whitelistTables("A", "CUSTOMER"), dsLimits(1000, 10), null);
        sensitiveRegistry.registerSensitive(DS_ID, "CUSTOMER", "PHONE",
                com.agenthub.ai.dbaccess.model.SensitiveLevel.HIGH);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        // 结果列 label 为外层引用名 p，真实数据来自逗号连接派生表内层 phone
        ResultSet rs = resultSet1Col("p", "13812348000");
        when(ps.getResultSet()).thenReturn(rs);
        DsConfigView view = DsConfigView.builder().dsId(DS_ID).dbType("MYSQL")
                .whitelist(whitelistTables("A", "CUSTOMER")).build();
        ResourceLimits limits = ResourceLimits.builder().maxRows(1000).maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.MASK).allowSelectStar(false).build();
        when(registry.resolve(DS_ID)).thenReturn(DataSourceContext.builder()
                .config(view).dataSource(ds).dialect(new MySqlDialect()).limits(limits).build());

        QueryResult result = executor.execute(
                request("SELECT p FROM a, (SELECT phone AS p FROM customer) x WHERE a.id = x.id"));

        assertThat(result.getSensitiveMasked()).isTrue();
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).get("p")).isEqualTo("138****8000");
    }

    @Test
    @DisplayName("allowSelectStar+MASK 下 SELECT * 叠加逗号连接派生表别名仍不可绕过脱敏")
    void should_mask_when_starOverCommaDerivedTableAlias() throws Exception {
        stubContext(whitelistTables("A", "CUSTOMER"), dsLimits(1000, 10), null);
        sensitiveRegistry.registerSensitive(DS_ID, "CUSTOMER", "PHONE",
                com.agenthub.ai.dbaccess.model.SensitiveLevel.HIGH);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        ResultSet rs = resultSet1Col("p", "13812348000");
        when(ps.getResultSet()).thenReturn(rs);
        DsConfigView view = DsConfigView.builder().dsId(DS_ID).dbType("MYSQL")
                .whitelist(whitelistTables("A", "CUSTOMER")).build();
        ResourceLimits limits = ResourceLimits.builder().maxRows(1000).maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.MASK).allowSelectStar(true).build();
        when(registry.resolve(DS_ID)).thenReturn(DataSourceContext.builder()
                .config(view).dataSource(ds).dialect(new MySqlDialect()).limits(limits).build());

        QueryResult result = executor.execute(
                request("SELECT * FROM a, (SELECT phone AS p FROM customer) x WHERE a.id = x.id"));

        assertThat(result.getSensitiveMasked()).isTrue();
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).get("p")).isEqualTo("138****8000");
    }

    @Test
    @DisplayName("DENY 策略下逗号连接多表派生表别名重命名同样被拒（文本命中预检）")
    void should_deny_when_commaDerivedTableAliasHidesSensitiveColumn() {
        stubContext(whitelistTables("A", "CUSTOMER"), dsLimits(1000, 10), null);
        sensitiveRegistry.registerSensitive(DS_ID, "CUSTOMER", "PHONE",
                com.agenthub.ai.dbaccess.model.SensitiveLevel.HIGH);
        assertThatThrownBy(() -> executor.execute(
                request("SELECT p FROM a, (SELECT phone AS p FROM customer) x WHERE a.id = x.id")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.SENSITIVE_DENIED.getCode()));
    }

    @Test
    @DisplayName("普通非派生表多表查询（无敏感标识符）MASK 下正常放行明文，不被误伤")
    void should_allow_when_plainMultiTableWithoutSensitive() throws Exception {
        stubContext(whitelistTables("A", "CUSTOMER"), dsLimits(1000, 10), null);
        sensitiveRegistry.registerSensitive(DS_ID, "CUSTOMER", "PHONE",
                com.agenthub.ai.dbaccess.model.SensitiveLevel.HIGH);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        ResultSet rs = resultSet1Col("id", "A1");
        when(ps.getResultSet()).thenReturn(rs);
        DsConfigView view = DsConfigView.builder().dsId(DS_ID).dbType("MYSQL")
                .whitelist(whitelistTables("A", "CUSTOMER")).build();
        ResourceLimits limits = ResourceLimits.builder().maxRows(1000).maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.MASK).allowSelectStar(false).build();
        when(registry.resolve(DS_ID)).thenReturn(DataSourceContext.builder()
                .config(view).dataSource(ds).dialect(new MySqlDialect()).limits(limits).build());

        QueryResult result = executor.execute(
                request("SELECT a.id FROM a, customer x WHERE a.id = x.id"));

        assertThat(result.getSensitiveMasked()).isFalse();
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).get("id")).isEqualTo("A1");
    }

    @Test
    @DisplayName("allowSelectStar+MASK 下 SELECT * FROM (派生表别名) 仍不可绕过脱敏")
    void should_mask_when_starOverDerivedTableAlias() throws Exception {
        stubContext(whitelistTables("CUSTOMER"), dsLimits(1000, 10), null);
        sensitiveRegistry.registerSensitive(DS_ID, "CUSTOMER", "PHONE",
                com.agenthub.ai.dbaccess.model.SensitiveLevel.HIGH);

        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        // 结果列 label 为内层别名 p，真实数据来自 phone（派生表 + SELECT * 展开）
        ResultSet rs = resultSet1Col("p", "13812348000");
        when(ps.getResultSet()).thenReturn(rs);
        DsConfigView view = DsConfigView.builder().dsId(DS_ID).dbType("MYSQL")
                .whitelist(whitelistTables("CUSTOMER")).build();
        ResourceLimits limits = ResourceLimits.builder().maxRows(1000).maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.MASK).allowSelectStar(true).build();
        when(registry.resolve(DS_ID)).thenReturn(DataSourceContext.builder()
                .config(view).dataSource(ds).dialect(new MySqlDialect()).limits(limits).build());

        QueryResult result = executor.execute(
                request("SELECT * FROM (SELECT phone AS p FROM customer) t"));

        assertThat(result.getSensitiveMasked()).isTrue();
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).get("p")).isEqualTo("138****8000");
    }

    // ------------------------------------------------------------------
    // 参数化 + PreparedStatement 行为
    // ------------------------------------------------------------------

    @Test
    @DisplayName("执行走 PreparedStatement 且设置查询超时与 maxRows")
    void should_usePreparedStatement_when_execute() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        ResultSet rs = resultSet1Col("id", 1);
        when(ps.getResultSet()).thenReturn(rs);

        stubContext(whitelistTables("T"), dsLimits(500, 5), ds);

        QueryResult result = executor.execute(request("SELECT id FROM t"));

        verify(conn).prepareStatement("SELECT id FROM t");
        verify(ps).setQueryTimeout(5);
        verify(ps).setMaxRows(501); // 有效 maxRows 500，多取 1 行探测截断
        assertThat(result.getRowCount()).isEqualTo(1);
        assertThat(result.getTruncated()).isFalse();
        assertThat(result.getDsId()).isEqualTo(DS_ID);
    }

    @Test
    @DisplayName("请求 maxRows 与数据源 maxRows 取交集（更小者生效并触发截断标记）")
    void should_truncate_when_requestMaxRowsSmaller() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        ResultSet rs = resultSet1Col("id", 1, 2, 3);
        when(ps.getResultSet()).thenReturn(rs);

        stubContext(whitelistTables("T"), dsLimits(500, 10), ds);

        QueryRequest req = request("SELECT id FROM t");
        req.setMaxRows(2);
        QueryResult result = executor.execute(req);

        verify(ps).setMaxRows(3); // min(500,2)+1 = 3
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.getTruncated()).isTrue();
        assertThat(result.getColumnNames()).containsExactly("id");
    }

    @Test
    @DisplayName("SQL 超时转 TIMEOUT 结构化异常")
    void should_translate_when_timeout() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenThrow(new SQLTimeoutException("timeout"));
        stubContext(whitelistTables("T"), dsLimits(1000, 10), ds);

        assertThatThrownBy(() -> executor.execute(request("SELECT id FROM t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> {
                    DbAccessException ex = (DbAccessException) e;
                    assertThat(ex.getCode()).isEqualTo(DbAccessErrorCode.TIMEOUT.getCode());
                    assertThat(ex.getMessage()).doesNotContain("timeout");
                });
    }

    @Test
    @DisplayName("连接失败（SQLState 08 开头）转 CONNECTION_FAILED")
    void should_translate_when_connectionFailed() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("conn refused", "08001"));
        stubContext(whitelistTables("T"), dsLimits(1000, 10), ds);

        assertThatThrownBy(() -> executor.execute(request("SELECT id FROM t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> {
                    DbAccessException ex = (DbAccessException) e;
                    assertThat(ex.getCode()).isEqualTo(DbAccessErrorCode.CONNECTION_FAILED.getCode());
                    assertThat(ex.getMessage()).doesNotContain("refused");
                });
    }

    @Test
    @DisplayName("SQL 语法错误（SQLState 42 开头）转 SQL_REJECTED")
    void should_translate_when_badSql() throws Exception {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("syntax", "42000"));
        stubContext(whitelistTables("T"), dsLimits(1000, 10), ds);

        assertThatThrownBy(() -> executor.execute(request("SELECT id FROM t")))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.SQL_REJECTED.getCode()));
    }

    // ------------------------------------------------------------------
    // EXPLAIN
    // ------------------------------------------------------------------

    @Test
    @DisplayName("explain 走 EXPLAIN 前缀执行并返回 explainPlan，不返回业务行")
    void should_explain_when_explainOnly() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        ResultSet rs = resultSet1Col("id", 1);
        when(ps.executeQuery()).thenReturn(rs);
        stubContext(whitelistTables("T"), dsLimits(1000, 10), ds);

        QueryRequest req = QueryRequest.builder().dsId(DS_ID).sql("SELECT id FROM t").explainOnly(true).build();
        QueryResult result = executor.explain(req);

        verify(conn).prepareStatement("EXPLAIN SELECT id FROM t");
        assertThat(result.getExplainPlan()).contains("id");
        assertThat(result.getRows()).hasSize(1);
    }

    @Test
    @DisplayName("explain 同样拒绝 DML（非 EXPLAIN/SELECT 前缀）")
    void should_reject_when_explainDml() {
        stubContext(whitelistTables("T"), dsLimits(1000, 10), null);
        QueryRequest req = QueryRequest.builder().dsId(DS_ID).sql("DELETE FROM t").explainOnly(true).build();
        assertThatThrownBy(() -> executor.explain(req))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.SQL_REJECTED.getCode()));
    }

    // ------------------------------------------------------------------
    // 允许 SELECT * 的数据源
    // ------------------------------------------------------------------

    @Test
    @DisplayName("allowSelectStar=true 时 SELECT * 放行并可执行")
    void should_allowSelectStar_when_dsAllows() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.execute()).thenReturn(true);
        ResultSet rs = resultSet1Col("id", 1);
        when(ps.getResultSet()).thenReturn(rs);

        DsConfigView view = DsConfigView.builder().dsId(DS_ID).dbType("MYSQL").whitelist(whitelistTables("T")).build();
        ResourceLimits limits = ResourceLimits.builder().maxRows(1000).maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.DENY).allowSelectStar(true).build();
        when(registry.resolve(DS_ID)).thenReturn(DataSourceContext.builder()
                .config(view).dataSource(ds).dialect(new MySqlDialect()).limits(limits).build());

        QueryResult result = executor.execute(request("SELECT * FROM t"));
        assertThat(result.getRowCount()).isEqualTo(1);
    }
}
