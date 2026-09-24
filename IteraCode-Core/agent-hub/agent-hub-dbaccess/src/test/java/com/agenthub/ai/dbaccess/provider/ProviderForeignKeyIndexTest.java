package com.agenthub.ai.dbaccess.provider;

import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.IndexMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.dbaccess.security.SensitiveFieldRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T002 三 Provider 外键真值采集与索引清单采集单测（不依赖真实数据库）。
 *
 * <p>以 SQL 内容匹配打桩（而非顺序打桩），锁定：外键列 {@code isForeignKey=1}、索引聚合/列序/
 * 唯一性/主键性、失败降级为空集合不阻断、以及默认开关关闭时零额外查询。</p>
 */
class ProviderForeignKeyIndexTest {

    private DataSourceRegistry registry;
    private SensitiveFieldRegistry sensitiveFieldRegistry;

    @BeforeEach
    void setUp() {
        registry = mock(DataSourceRegistry.class);
        sensitiveFieldRegistry = mock(SensitiveFieldRegistry.class);
        when(sensitiveFieldRegistry.loadByDs(anyString())).thenReturn(List.of());
    }

    private DataSourceContext contextWith(DsConfigView config, Connection conn) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(conn);
        return DataSourceContext.builder().config(config).dataSource(dataSource).build();
    }

    private DsConfigView mysqlConfig() {
        return DsConfigView.builder().dsId("ds-mysql").dbType(DbType.MYSQL.getCode()).spaceName("testdb").build();
    }

    private DsConfigView oracleConfig() {
        return DsConfigView.builder().dsId("ds-ora").dbType("ORACLE").spaceName("crm").build();
    }

    private DsConfigView fallbackConfig() {
        return DsConfigView.builder().dsId("ds-fb").dbType("POSTGRESQL").spaceName("testdb").build();
    }

    // ------------------------------------------------------------------
    // MySQL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MySQL listColumns includeForeignKeys=true：外键列 isForeignKey=1，其余列=0（SQL 参数化）")
    void should_markForeignKeyColumns_when_mysqlListColumns() throws Exception {
        MySqlProvider provider = new MySqlProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet fkRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "t_order", "COLUMN_NAME", "customer_id")));
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("COLUMN_NAME", "customer_id", "ORDINAL_POSITION", 1, "DATA_TYPE", "bigint",
                        "CHARACTER_MAXIMUM_LENGTH", null, "NUMERIC_PRECISION", 19, "NUMERIC_SCALE", 0,
                        "IS_NULLABLE", "YES", "COLUMN_KEY", "", "COLUMN_COMMENT", "客户ID"),
                ResultSetMocks.row("COLUMN_NAME", "id", "ORDINAL_POSITION", 2, "DATA_TYPE", "bigint",
                        "CHARACTER_MAXIMUM_LENGTH", null, "NUMERIC_PRECISION", 19, "NUMERIC_SCALE", 0,
                        "IS_NULLABLE", "NO", "COLUMN_KEY", "PRI", "COLUMN_COMMENT", "主键")));
        when(conn.prepareStatement(contains("KEY_COLUMN_USAGE"))).thenReturn(fkPs);
        when(conn.prepareStatement(contains("information_schema.COLUMNS"))).thenReturn(colPs);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        when(colPs.executeQuery()).thenReturn(colRs);
        DataSourceContext context = contextWith(mysqlConfig(), conn);
        when(registry.resolve("ds-mysql")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(MetadataRequest.builder()
                .dsId("ds-mysql").includeForeignKeys(true).build(), "t_order");

        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).getColumnName()).isEqualTo("customer_id");
        assertThat(columns.get(0).getIsForeignKey()).isEqualTo(1);
        assertThat(columns.get(1).getColumnName()).isEqualTo("id");
        assertThat(columns.get(1).getIsForeignKey()).isZero();
        assertThat(columns.get(1).getIsPrimaryKey()).isEqualTo(1);
        verify(fkPs).setString(1, "testdb");
        verify(colPs).setString(1, "testdb");
    }

    @Test
    @DisplayName("MySQL listColumns 默认开关关闭：仅 1 次 prepareStatement（零额外查询）且不含外键/索引 SQL")
    void should_issueZeroExtraQuery_when_mysqlTogglesOff() throws Exception {
        MySqlProvider provider = new MySqlProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("COLUMN_NAME", "id", "ORDINAL_POSITION", 1, "DATA_TYPE", "bigint",
                        "CHARACTER_MAXIMUM_LENGTH", null, "NUMERIC_PRECISION", 19, "NUMERIC_SCALE", 0,
                        "IS_NULLABLE", "NO", "COLUMN_KEY", "PRI", "COLUMN_COMMENT", null)));
        when(conn.prepareStatement(anyString())).thenReturn(colPs);
        when(colPs.executeQuery()).thenReturn(colRs);
        DataSourceContext context = contextWith(mysqlConfig(), conn);
        when(registry.resolve("ds-mysql")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-mysql").build(), "t_order");

        assertThat(columns).hasSize(1);
        assertThat(columns.get(0).getIsForeignKey()).isZero();
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conn, times(1)).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .doesNotContain("KEY_COLUMN_USAGE")
                .doesNotContain("STATISTICS");
    }

    @Test
    @DisplayName("MySQL listTables includeIndexes=true：PRIMARY(isPrimary=1,isUnique=1) + 复合索引按 SEQ_IN_INDEX 升序")
    void should_collectIndexes_when_mysqlListTables() throws Exception {
        MySqlProvider provider = new MySqlProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        PreparedStatement tablesPs = mock(PreparedStatement.class);
        PreparedStatement idxPs = mock(PreparedStatement.class);
        ResultSet tablesRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "t_order", "TABLE_COMMENT", "订单表", "TABLE_TYPE", "TABLE")));
        ResultSet idxRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "t_order", "INDEX_NAME", "PRIMARY", "NON_UNIQUE", 0,
                        "COLUMN_NAME", "id", "INDEX_TYPE", "BTREE"),
                ResultSetMocks.row("TABLE_NAME", "t_order", "INDEX_NAME", "idx_code_name", "NON_UNIQUE", 0,
                        "COLUMN_NAME", "code", "INDEX_TYPE", "BTREE"),
                ResultSetMocks.row("TABLE_NAME", "t_order", "INDEX_NAME", "idx_code_name", "NON_UNIQUE", 0,
                        "COLUMN_NAME", "name", "INDEX_TYPE", "BTREE"),
                ResultSetMocks.row("TABLE_NAME", "t_order", "INDEX_NAME", "idx_status", "NON_UNIQUE", 1,
                        "COLUMN_NAME", "status", "INDEX_TYPE", "BTREE")));
        when(conn.prepareStatement(contains("information_schema.TABLES"))).thenReturn(tablesPs);
        when(conn.prepareStatement(contains("information_schema.STATISTICS"))).thenReturn(idxPs);
        when(tablesPs.executeQuery()).thenReturn(tablesRs);
        when(idxPs.executeQuery()).thenReturn(idxRs);
        DataSourceContext context = contextWith(mysqlConfig(), conn);
        when(registry.resolve("ds-mysql")).thenReturn(context);

        List<TableMeta> tables = provider.listTables(MetadataRequest.builder()
                .dsId("ds-mysql").includeColumns(false).includeIndexes(true).build());

        assertThat(tables).hasSize(1);
        List<IndexMeta> indexes = tables.get(0).getIndexes();
        assertThat(indexes).hasSize(3);
        assertThat(indexes).extracting(IndexMeta::getIndexName)
                .containsExactly("PRIMARY", "idx_code_name", "idx_status");

        IndexMeta primary = indexes.get(0);
        assertThat(primary.getIsPrimary()).isEqualTo(1);
        assertThat(primary.getIsUnique()).isEqualTo(1);
        assertThat(primary.getColumns()).containsExactly("id");
        assertThat(primary.getIndexType()).isEqualTo("BTREE");

        IndexMeta composite = indexes.get(1);
        assertThat(composite.getColumns()).containsExactly("code", "name");
        assertThat(composite.getIsUnique()).isEqualTo(1);
        assertThat(composite.getIsPrimary()).isZero();

        assertThat(indexes.get(2).getIsUnique()).isZero();
    }

    @Test
    @DisplayName("MySQL listTables(includeColumns+includeForeignKeys=true)：attachColumns 路径回填外键列 isForeignKey=1，其余列=0")
    void should_markForeignKeyColumns_when_mysqlListTablesWithColumns() throws Exception {
        MySqlProvider provider = new MySqlProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        PreparedStatement tablesPs = mock(PreparedStatement.class);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet tablesRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "t_order", "TABLE_COMMENT", "订单表", "TABLE_TYPE", "TABLE")));
        ResultSet fkRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "t_order", "COLUMN_NAME", "customer_id")));
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "t_order", "COLUMN_NAME", "customer_id", "ORDINAL_POSITION", 1,
                        "DATA_TYPE", "bigint", "CHARACTER_MAXIMUM_LENGTH", null,
                        "NUMERIC_PRECISION", 19, "NUMERIC_SCALE", 0, "IS_NULLABLE", "YES",
                        "COLUMN_KEY", "", "COLUMN_COMMENT", "客户ID"),
                ResultSetMocks.row("TABLE_NAME", "t_order", "COLUMN_NAME", "id", "ORDINAL_POSITION", 2,
                        "DATA_TYPE", "bigint", "CHARACTER_MAXIMUM_LENGTH", null,
                        "NUMERIC_PRECISION", 19, "NUMERIC_SCALE", 0, "IS_NULLABLE", "NO",
                        "COLUMN_KEY", "PRI", "COLUMN_COMMENT", "主键")));
        when(conn.prepareStatement(contains("information_schema.TABLES"))).thenReturn(tablesPs);
        when(conn.prepareStatement(contains("KEY_COLUMN_USAGE"))).thenReturn(fkPs);
        when(conn.prepareStatement(contains("information_schema.COLUMNS"))).thenReturn(colPs);
        when(tablesPs.executeQuery()).thenReturn(tablesRs);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        when(colPs.executeQuery()).thenReturn(colRs);
        DataSourceContext context = contextWith(mysqlConfig(), conn);
        when(registry.resolve("ds-mysql")).thenReturn(context);

        List<TableMeta> tables = provider.listTables(MetadataRequest.builder()
                .dsId("ds-mysql").includeColumns(true).includeForeignKeys(true).build());

        assertThat(tables).hasSize(1);
        List<ColumnMeta> columns = tables.get(0).getColumns();
        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).getColumnName()).isEqualTo("customer_id");
        assertThat(columns.get(0).getIsForeignKey()).isEqualTo(1);
        assertThat(columns.get(1).getColumnName()).isEqualTo("id");
        assertThat(columns.get(1).getIsForeignKey()).isZero();
        assertThat(columns.get(1).getIsPrimaryKey()).isEqualTo(1);
        // 默认 includeIndexes=false → indexes 保持空集合（非 null），与改造前一致
        assertThat(tables.get(0).getIndexes()).isEmpty();
        verify(fkPs).setString(1, "testdb");
    }

    @Test
    @DisplayName("MySQL listTables 默认开关关闭：仅 TABLES+COLUMNS 两次查询，无外键/索引 SQL，indexes 空集合")
    void should_issueZeroExtraQuery_when_mysqlListTablesTogglesOff() throws Exception {
        MySqlProvider provider = new MySqlProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        PreparedStatement tablesPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet tablesRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "t_order", "TABLE_COMMENT", null, "TABLE_TYPE", "TABLE")));
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "t_order", "COLUMN_NAME", "id", "ORDINAL_POSITION", 1,
                        "DATA_TYPE", "bigint", "CHARACTER_MAXIMUM_LENGTH", null,
                        "NUMERIC_PRECISION", 19, "NUMERIC_SCALE", 0, "IS_NULLABLE", "NO",
                        "COLUMN_KEY", "PRI", "COLUMN_COMMENT", null)));
        when(conn.prepareStatement(contains("information_schema.TABLES"))).thenReturn(tablesPs);
        when(conn.prepareStatement(contains("information_schema.COLUMNS"))).thenReturn(colPs);
        when(tablesPs.executeQuery()).thenReturn(tablesRs);
        when(colPs.executeQuery()).thenReturn(colRs);
        DataSourceContext context = contextWith(mysqlConfig(), conn);
        when(registry.resolve("ds-mysql")).thenReturn(context);

        List<TableMeta> tables = provider.listTables(MetadataRequest.builder()
                .dsId("ds-mysql").includeColumns(true).build());

        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).getIndexes()).isEmpty();
        assertThat(tables.get(0).getColumns().get(0).getIsForeignKey()).isZero();
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conn, times(2)).prepareStatement(sql.capture());
        for (String statement : sql.getAllValues()) {
            assertThat(statement).doesNotContain("KEY_COLUMN_USAGE").doesNotContain("STATISTICS");
        }
    }

    @Test
    @DisplayName("MySQL：schema 含特殊字符（a'b）仍走 PreparedStatement 参数绑定，无字符串拼接")
    void should_bindSchemaParameter_when_specialCharSchema() throws Exception {
        MySqlProvider provider = new MySqlProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet emptyFkRs = ResultSetMocks.rows(List.of());
        ResultSet emptyColRs = ResultSetMocks.rows(List.of());
        when(conn.prepareStatement(contains("KEY_COLUMN_USAGE"))).thenReturn(fkPs);
        when(conn.prepareStatement(contains("information_schema.COLUMNS"))).thenReturn(colPs);
        when(fkPs.executeQuery()).thenReturn(emptyFkRs);
        when(colPs.executeQuery()).thenReturn(emptyColRs);
        DataSourceContext context = contextWith(mysqlConfig(), conn);
        when(registry.resolve("ds-mysql")).thenReturn(context);

        provider.listColumns(MetadataRequest.builder()
                .dsId("ds-mysql").spaceName("a'b").includeForeignKeys(true).build(), "t_order");

        verify(fkPs).setString(1, "a'b");
        verify(colPs).setString(1, "a'b");
    }

    // ------------------------------------------------------------------
    // Oracle
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Oracle listColumns：小写表名归一命中，外键列 isForeignKey=1 且主键列=0")
    void should_markForeignKeyColumns_when_oracleListColumnsLowercase() throws Exception {
        OracleProvider provider = new OracleProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        PreparedStatement pkPs = mock(PreparedStatement.class);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet pkRs = ResultSetMocks.rows(List.of(ResultSetMocks.row("COLUMN_NAME", "ID")));
        ResultSet fkRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "CUSTOMER", "COLUMN_NAME", "CUST_ID")));
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("COLUMN_NAME", "ID", "COLUMN_ID", 1, "DATA_TYPE", "NUMBER",
                        "DATA_LENGTH", 22, "CHAR_LENGTH", null, "DATA_PRECISION", 10, "DATA_SCALE", 0,
                        "NULLABLE", "N", "COLUMN_COMMENT", "主键"),
                ResultSetMocks.row("COLUMN_NAME", "CUST_ID", "COLUMN_ID", 2, "DATA_TYPE", "NUMBER",
                        "DATA_LENGTH", 22, "CHAR_LENGTH", null, "DATA_PRECISION", 10, "DATA_SCALE", 0,
                        "NULLABLE", "Y", "COLUMN_COMMENT", "客户ID")));
        when(conn.prepareStatement(contains("AND ac.TABLE_NAME = ?"))).thenReturn(pkPs);
        when(conn.prepareStatement(contains("CONSTRAINT_TYPE = 'R'"))).thenReturn(fkPs);
        when(conn.prepareStatement(contains("ALL_TAB_COLUMNS"))).thenReturn(colPs);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        when(colPs.executeQuery()).thenReturn(colRs);
        DataSourceContext context = contextWith(oracleConfig(), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(MetadataRequest.builder()
                .dsId("ds-ora").includeForeignKeys(true).build(), "customer");

        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).getColumnName()).isEqualTo("ID");
        assertThat(columns.get(0).getIsPrimaryKey()).isEqualTo(1);
        assertThat(columns.get(0).getIsForeignKey()).isZero();
        assertThat(columns.get(1).getColumnName()).isEqualTo("CUST_ID");
        assertThat(columns.get(1).getIsForeignKey()).isEqualTo(1);
        // OWNER 大写归一后参数绑定
        verify(fkPs).setString(1, "CRM");
    }

    @Test
    @DisplayName("Oracle listTables includeIndexes=true：索引名=主键约束名 → isPrimary=1，UNIQUE → isUnique=1")
    void should_collectIndexes_when_oracleListTables() throws Exception {
        OracleProvider provider = new OracleProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        PreparedStatement tablesPs = mock(PreparedStatement.class);
        PreparedStatement idxPs = mock(PreparedStatement.class);
        ResultSet tablesRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "T", "TABLE_TYPE", "TABLE", "TABLE_COMMENT", "表")));
        // 主键约束名判定已折叠进索引查询（LEFT JOIN 列 PK_CONSTRAINT_NAME）：PK 索引非空，普通索引为 null
        ResultSet idxRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "T", "INDEX_NAME", "PK_T", "UNIQUENESS", "UNIQUE",
                        "INDEX_TYPE", "NORMAL", "COLUMN_NAME", "ID", "COLUMN_POSITION", 1,
                        "PK_CONSTRAINT_NAME", "PK_T"),
                ResultSetMocks.row("TABLE_NAME", "T", "INDEX_NAME", "IDX_NAME", "UNIQUENESS", "NONUNIQUE",
                        "INDEX_TYPE", "NORMAL", "COLUMN_NAME", "NAME", "COLUMN_POSITION", 1,
                        "PK_CONSTRAINT_NAME", null)));
        when(conn.prepareStatement(contains("ALL_TABLES"))).thenReturn(tablesPs);
        when(conn.prepareStatement(contains("ALL_INDEXES"))).thenReturn(idxPs);
        when(tablesPs.executeQuery()).thenReturn(tablesRs);
        when(idxPs.executeQuery()).thenReturn(idxRs);
        DataSourceContext context = contextWith(oracleConfig(), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<TableMeta> tables = provider.listTables(MetadataRequest.builder()
                .dsId("ds-ora").includeColumns(false).includeIndexes(true).build());

        assertThat(tables).hasSize(1);
        List<IndexMeta> indexes = tables.get(0).getIndexes();
        assertThat(indexes).hasSize(2);
        assertThat(indexes.get(0).getIndexName()).isEqualTo("PK_T");
        assertThat(indexes.get(0).getIsPrimary()).isEqualTo(1);
        assertThat(indexes.get(0).getIsUnique()).isEqualTo(1);
        assertThat(indexes.get(1).getIsPrimary()).isZero();
        assertThat(indexes.get(1).getIsUnique()).isZero();
        // 第 3 次整库「主键约束名」往返已折叠进索引查询：不再出现独立的 ALL_CONSTRAINTS ac WHERE 查询
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conn, times(2)).prepareStatement(sql.capture());
        for (String statement : sql.getAllValues()) {
            assertThat(statement).doesNotContain("ALL_CONSTRAINTS ac WHERE");
        }
        assertThat(sql.getAllValues().stream().filter(s -> s.contains("ALL_INDEXES")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Oracle 双开关同开：每库新增查询恰为 2（外键列 1 + 索引 1），无独立主键约束名往返（NFR-BE-004）")
    void should_issueTwoExtraQueries_when_oracleForeignKeyAndIndexTogglesOn() throws Exception {
        OracleProvider provider = new OracleProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        PreparedStatement tablesPs = mock(PreparedStatement.class);
        PreparedStatement pkPs = mock(PreparedStatement.class);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        PreparedStatement idxPs = mock(PreparedStatement.class);
        ResultSet tablesRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "T", "TABLE_TYPE", "TABLE", "TABLE_COMMENT", "表")));
        ResultSet pkRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "T", "COLUMN_NAME", "ID")));
        ResultSet fkRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "T", "COLUMN_NAME", "CUST_ID")));
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "T", "COLUMN_NAME", "ID", "COLUMN_ID", 1, "DATA_TYPE", "NUMBER",
                        "DATA_LENGTH", 22, "CHAR_LENGTH", null, "DATA_PRECISION", 10, "DATA_SCALE", 0,
                        "NULLABLE", "N", "COLUMN_COMMENT", "主键"),
                ResultSetMocks.row("TABLE_NAME", "T", "COLUMN_NAME", "CUST_ID", "COLUMN_ID", 2, "DATA_TYPE", "NUMBER",
                        "DATA_LENGTH", 22, "CHAR_LENGTH", null, "DATA_PRECISION", 10, "DATA_SCALE", 0,
                        "NULLABLE", "Y", "COLUMN_COMMENT", "客户ID")));
        ResultSet idxRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "T", "INDEX_NAME", "PK_T", "UNIQUENESS", "UNIQUE",
                        "INDEX_TYPE", "NORMAL", "COLUMN_NAME", "ID", "COLUMN_POSITION", 1,
                        "PK_CONSTRAINT_NAME", "PK_T"),
                ResultSetMocks.row("TABLE_NAME", "T", "INDEX_NAME", "IDX_CUST", "UNIQUENESS", "NONUNIQUE",
                        "INDEX_TYPE", "NORMAL", "COLUMN_NAME", "CUST_ID", "COLUMN_POSITION", 1,
                        "PK_CONSTRAINT_NAME", null)));
        when(conn.prepareStatement(contains("ALL_TABLES"))).thenReturn(tablesPs);
        when(conn.prepareStatement(contains("CONSTRAINT_TYPE = 'P' AND ac.OWNER = ?"))).thenReturn(pkPs);
        when(conn.prepareStatement(contains("CONSTRAINT_TYPE = 'R'"))).thenReturn(fkPs);
        when(conn.prepareStatement(contains("ALL_TAB_COLUMNS"))).thenReturn(colPs);
        when(conn.prepareStatement(contains("ALL_INDEXES"))).thenReturn(idxPs);
        when(tablesPs.executeQuery()).thenReturn(tablesRs);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        when(colPs.executeQuery()).thenReturn(colRs);
        when(idxPs.executeQuery()).thenReturn(idxRs);
        DataSourceContext context = contextWith(oracleConfig(), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<TableMeta> tables = provider.listTables(MetadataRequest.builder()
                .dsId("ds-ora").includeColumns(true).includeForeignKeys(true).includeIndexes(true).build());

        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).getColumns().get(1).getIsForeignKey()).isEqualTo(1);
        assertThat(tables.get(0).getIndexes().get(0).getIsPrimary()).isEqualTo(1);
        assertThat(tables.get(0).getIndexes().get(1).getIsPrimary()).isZero();

        // 新增查询 = 外键列查询 1 次 + 索引查询 1 次，合计 2（主键约束名判定已并入索引查询）
        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conn, times(5)).prepareStatement(sql.capture());
        List<String> statements = sql.getAllValues();
        for (String statement : statements) {
            assertThat(statement).doesNotContain("ALL_CONSTRAINTS ac WHERE");
        }
        assertThat(statements.stream().filter(s -> s.contains("CONSTRAINT_TYPE = 'R'")).count()).isEqualTo(1);
        assertThat(statements.stream().filter(s -> s.contains("ALL_INDEXES")).count()).isEqualTo(1);
        assertThat(statements.stream()
                .filter(s -> s.contains("CONSTRAINT_TYPE = 'R'") || s.contains("ALL_INDEXES"))
                .count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Oracle listTables(includeColumns+includeForeignKeys=true)：attachColumns 路径大写归一回填外键列 isForeignKey=1")
    void should_markForeignKeyColumns_when_oracleListTablesWithColumns() throws Exception {
        OracleProvider provider = new OracleProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        PreparedStatement tablesPs = mock(PreparedStatement.class);
        PreparedStatement pkPs = mock(PreparedStatement.class);
        PreparedStatement fkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet tablesRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "CUSTOMER", "TABLE_TYPE", "TABLE", "TABLE_COMMENT", "客户表")));
        ResultSet pkRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "CUSTOMER", "COLUMN_NAME", "ID")));
        ResultSet fkRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "CUSTOMER", "COLUMN_NAME", "CUST_ID")));
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "CUSTOMER", "COLUMN_NAME", "ID", "COLUMN_ID", 1,
                        "DATA_TYPE", "NUMBER", "DATA_LENGTH", 22, "CHAR_LENGTH", null,
                        "DATA_PRECISION", 10, "DATA_SCALE", 0, "NULLABLE", "N", "COLUMN_COMMENT", "主键"),
                ResultSetMocks.row("TABLE_NAME", "CUSTOMER", "COLUMN_NAME", "CUST_ID", "COLUMN_ID", 2,
                        "DATA_TYPE", "NUMBER", "DATA_LENGTH", 22, "CHAR_LENGTH", null,
                        "DATA_PRECISION", 10, "DATA_SCALE", 0, "NULLABLE", "Y", "COLUMN_COMMENT", "客户ID")));
        when(conn.prepareStatement(contains("ALL_TABLES"))).thenReturn(tablesPs);
        when(conn.prepareStatement(contains("CONSTRAINT_TYPE = 'P'"))).thenReturn(pkPs);
        when(conn.prepareStatement(contains("CONSTRAINT_TYPE = 'R'"))).thenReturn(fkPs);
        when(conn.prepareStatement(contains("ALL_TAB_COLUMNS"))).thenReturn(colPs);
        when(tablesPs.executeQuery()).thenReturn(tablesRs);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        when(fkPs.executeQuery()).thenReturn(fkRs);
        when(colPs.executeQuery()).thenReturn(colRs);
        DataSourceContext context = contextWith(oracleConfig(), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<TableMeta> tables = provider.listTables(MetadataRequest.builder()
                .dsId("ds-ora").includeColumns(true).includeForeignKeys(true).build());

        assertThat(tables).hasSize(1);
        List<ColumnMeta> columns = tables.get(0).getColumns();
        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).getColumnName()).isEqualTo("ID");
        assertThat(columns.get(0).getIsPrimaryKey()).isEqualTo(1);
        assertThat(columns.get(0).getIsForeignKey()).isZero();
        assertThat(columns.get(1).getColumnName()).isEqualTo("CUST_ID");
        assertThat(columns.get(1).getIsForeignKey()).isEqualTo(1);
        assertThat(tables.get(0).getIndexes()).isEmpty();
        verify(fkPs).setString(1, "CRM");
    }

    // ------------------------------------------------------------------
    // JdbcFallback
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Fallback A-1.4：getImportedKeys 抛 SQLException → 不抛异常，全部列 isForeignKey=0 且元数据正常")
    void should_degradeForeignKeys_when_fallbackDriverUnsupported() throws Exception {
        JdbcFallbackProvider provider = new JdbcFallbackProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(meta);
        ResultSet pkRs = ResultSetMocks.rows(List.of());
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("COLUMN_NAME", "AMOUNT", "ORDINAL_POSITION", 1, "TYPE_NAME", "DECIMAL",
                        "COLUMN_SIZE", 12, "DECIMAL_DIGITS", 4, "NULLABLE", 1, "REMARKS", "金额")));
        when(meta.getPrimaryKeys(null, "testdb", "t_order")).thenReturn(pkRs);
        when(meta.getImportedKeys(null, "testdb", "t_order"))
                .thenThrow(new SQLException("driver unsupported"));
        when(meta.getImportedKeys("testdb", null, "t_order"))
                .thenThrow(new SQLException("driver unsupported"));
        when(meta.getColumns(null, "testdb", "t_order", "%")).thenReturn(colRs);
        DataSourceContext context = contextWith(fallbackConfig(), conn);
        when(registry.resolve("ds-fb")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(MetadataRequest.builder()
                .dsId("ds-fb").includeForeignKeys(true).build(), "t_order");

        assertThat(columns).hasSize(1);
        assertThat(columns.get(0).getColumnName()).isEqualTo("AMOUNT");
        assertThat(columns.get(0).getIsForeignKey()).isZero();
        assertThat(columns.get(0).getNumericPrecision()).isEqualTo(12);
    }

    @Test
    @DisplayName("Fallback listTables includeIndexes=true：索引按 INDEX_NAME 分组，PK 列集合一致 → isPrimary=1")
    void should_collectIndexes_when_fallbackListTables() throws Exception {
        JdbcFallbackProvider provider = new JdbcFallbackProvider(registry, sensitiveFieldRegistry);
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(meta);
        ResultSet tablesRs = ResultSetMocks.rows(List.of(ResultSetMocks.row("TABLE_NAME", "t_order")));
        ResultSet pkRs = ResultSetMocks.rows(List.of(ResultSetMocks.row("COLUMN_NAME", "ID")));
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("COLUMN_NAME", "ID", "ORDINAL_POSITION", 1, "TYPE_NAME", "BIGINT",
                        "COLUMN_SIZE", 19, "DECIMAL_DIGITS", 0, "NULLABLE", 0, "REMARKS", null),
                ResultSetMocks.row("COLUMN_NAME", "NAME", "ORDINAL_POSITION", 2, "TYPE_NAME", "VARCHAR",
                        "COLUMN_SIZE", 32, "DECIMAL_DIGITS", null, "NULLABLE", 1, "REMARKS", null)));
        ResultSet idxRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("INDEX_NAME", "PRIMARY", "NON_UNIQUE", 0,
                        "COLUMN_NAME", "ID", "ORDINAL_POSITION", 1),
                ResultSetMocks.row("INDEX_NAME", "IDX_NAME", "NON_UNIQUE", 1,
                        "COLUMN_NAME", "NAME", "ORDINAL_POSITION", 1)));
        when(meta.getTables("testdb", null, "%", new String[]{"TABLE", "VIEW"})).thenReturn(tablesRs);
        when(meta.getPrimaryKeys(null, "testdb", "t_order")).thenReturn(pkRs);
        when(meta.getColumns(null, "testdb", "t_order", "%")).thenReturn(colRs);
        when(meta.getIndexInfo(null, "testdb", "t_order", false, false)).thenReturn(idxRs);
        DataSourceContext context = contextWith(fallbackConfig(), conn);
        when(registry.resolve("ds-fb")).thenReturn(context);

        List<TableMeta> tables = provider.listTables(MetadataRequest.builder()
                .dsId("ds-fb").includeColumns(true).includeIndexes(true).build());

        assertThat(tables).hasSize(1);
        List<IndexMeta> indexes = tables.get(0).getIndexes();
        assertThat(indexes).hasSize(2);
        assertThat(indexes.get(0).getIndexName()).isEqualTo("PRIMARY");
        assertThat(indexes.get(0).getIsPrimary()).isEqualTo(1);
        assertThat(indexes.get(0).getIsUnique()).isEqualTo(1);
        assertThat(indexes.get(1).getIndexName()).isEqualTo("IDX_NAME");
        assertThat(indexes.get(1).getIsPrimary()).isZero();
        assertThat(indexes.get(1).getIsUnique()).isZero();
    }
}
