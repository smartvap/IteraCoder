package com.agenthub.ai.dbaccess.provider;

import com.agenthub.ai.dbaccess.meta.ColumnMeta;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySqlProvider 列读取单测：验证 {@code NUMERIC_PRECISION}/{@code NUMERIC_SCALE} 的填充
 * （NULL 严格为 null、标度 0 保留为 0）与既有 {@code columnSize}（CHARACTER_MAXIMUM_LENGTH）语义。
 *
 * <p>使用 {@link ResultSetMocks} 有状态桩，避免 Mockito 默认 {@code wasNull=false} 把 NULL 折叠成 0 的失真。</p>
 */
class MySqlProviderTest {

    private DataSourceRegistry registry;
    private SensitiveFieldRegistry sensitiveFieldRegistry;
    private MySqlProvider provider;

    @BeforeEach
    void setUp() {
        registry = mock(DataSourceRegistry.class);
        sensitiveFieldRegistry = mock(SensitiveFieldRegistry.class);
        when(sensitiveFieldRegistry.loadByDs(anyString())).thenReturn(List.of());
        provider = new MySqlProvider(registry, sensitiveFieldRegistry);
    }

    private DataSourceContext contextWith(DsConfigView config, Connection conn) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(conn);
        return DataSourceContext.builder().config(config).dataSource(dataSource).build();
    }

    private DsConfigView config() {
        return DsConfigView.builder()
                .dsId("ds-mysql")
                .dbType(DbType.MYSQL.getCode())
                .spaceName("testdb")
                .build();
    }

    @Test
    @DisplayName("listColumns：数值列填充 NUMERIC_PRECISION/NUMERIC_SCALE；字符列精度/标度为 null、columnSize=字符长度")
    void should_fillNumericMeta_when_listColumns() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = ResultSetMocks.rows(List.of(
                // amount DECIMAL(10,2)：数值族，CHARACTER_MAXIMUM_LENGTH 为 NULL
                ResultSetMocks.row("COLUMN_NAME", "amount", "ORDINAL_POSITION", 1, "DATA_TYPE", "decimal",
                        "CHARACTER_MAXIMUM_LENGTH", null, "NUMERIC_PRECISION", 10, "NUMERIC_SCALE", 2,
                        "IS_NULLABLE", "YES", "COLUMN_KEY", "", "COLUMN_COMMENT", "金额"),
                // name VARCHAR(64)：长度族，NUMERIC_* 为 NULL
                ResultSetMocks.row("COLUMN_NAME", "name", "ORDINAL_POSITION", 2, "DATA_TYPE", "varchar",
                        "CHARACTER_MAXIMUM_LENGTH", 64, "NUMERIC_PRECISION", null, "NUMERIC_SCALE", null,
                        "IS_NULLABLE", "NO", "COLUMN_KEY", "", "COLUMN_COMMENT", "名称"),
                // id BIGINT：整数族，NUMERIC_SCALE=0 是合法值（必须保留为 0，不得变 null）
                ResultSetMocks.row("COLUMN_NAME", "id", "ORDINAL_POSITION", 3, "DATA_TYPE", "bigint",
                        "CHARACTER_MAXIMUM_LENGTH", null, "NUMERIC_PRECISION", 19, "NUMERIC_SCALE", 0,
                        "IS_NULLABLE", "NO", "COLUMN_KEY", "PRI", "COLUMN_COMMENT", null)
        ));
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);

        DataSourceContext context = contextWith(config(), conn);
        when(registry.resolve("ds-mysql")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-mysql").includeColumns(true).build(), "t_order");

        assertThat(columns).hasSize(3);

        ColumnMeta amount = columns.get(0);
        assertThat(amount.getColumnName()).isEqualTo("amount");
        assertThat(amount.getDataType()).isEqualTo("decimal");
        assertThat(amount.getNumericPrecision()).isEqualTo(10);
        assertThat(amount.getNumericScale()).isEqualTo(2);

        ColumnMeta name = columns.get(1);
        assertThat(name.getColumnName()).isEqualTo("name");
        // 非数值型：information_schema 的 NUMERIC_PRECISION/SCALE 为 NULL → 必须为 null（不是 0）
        assertThat(name.getNumericPrecision()).isNull();
        assertThat(name.getNumericScale()).isNull();
        // 既有语义不变：字符族 columnSize 取 CHARACTER_MAXIMUM_LENGTH
        assertThat(name.getColumnSize()).isEqualTo(64);

        ColumnMeta id = columns.get(2);
        assertThat(id.getNumericPrecision()).isEqualTo(19);
        // 标度 0 是合法值，必须保留为 0（禁止与 NULL 混淆）
        assertThat(id.getNumericScale()).isZero();

        verify(ps).setString(1, "testdb");
        verify(ps).setString(2, "t_order");
    }

    @Test
    @DisplayName("listColumns：数值列 columnSize 为既有口径（受既有判定顺序影响实测为 0，非 null）")
    void should_recordNumericColumnSize_when_listColumns() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("COLUMN_NAME", "amount", "ORDINAL_POSITION", 1, "DATA_TYPE", "decimal",
                        "CHARACTER_MAXIMUM_LENGTH", null, "NUMERIC_PRECISION", 10, "NUMERIC_SCALE", 2,
                        "IS_NULLABLE", "YES", "COLUMN_KEY", "", "COLUMN_COMMENT", null)
        ));
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        DataSourceContext context = contextWith(config(), conn);
        when(registry.resolve("ds-mysql")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-mysql").build(), "t_order");

        assertThat(columns).hasSize(1);
        // 既有实现：columnSize 的 null 判定发生在 DATA_TYPE 读取之后（见 toColumnMeta 参数求值顺序），
        // 数值列（DATA_TYPE 非空、CHARACTER_MAXIMUM_LENGTH 为空）实测得到 0 而非 null。
        // 该行为改动前后一致（T001 未触碰该行），本用例锁定实际行为并供报告记录偏差。
        assertThat(columns.get(0).getColumnSize()).isZero();
        assertThat(columns.get(0).getNumericPrecision()).isEqualTo(10);
        assertThat(columns.get(0).getNumericScale()).isEqualTo(2);
    }

    @Test
    @DisplayName("listTables(includeColumns)：attachColumns 路径与 listColumns 同口径填充精度/标度")
    void should_fillNumericMeta_when_listTablesWithColumns() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement tablesPs = mock(PreparedStatement.class);
        PreparedStatement colsPs = mock(PreparedStatement.class);
        ResultSet tablesRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "t_order", "TABLE_COMMENT", "订单表", "TABLE_TYPE", "TABLE")
        ));
        ResultSet colsRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("TABLE_NAME", "t_order", "COLUMN_NAME", "amount", "ORDINAL_POSITION", 1,
                        "DATA_TYPE", "decimal", "CHARACTER_MAXIMUM_LENGTH", null,
                        "NUMERIC_PRECISION", 12, "NUMERIC_SCALE", 4,
                        "IS_NULLABLE", "YES", "COLUMN_KEY", "", "COLUMN_COMMENT", "金额"),
                ResultSetMocks.row("TABLE_NAME", "t_order", "COLUMN_NAME", "code", "ORDINAL_POSITION", 2,
                        "DATA_TYPE", "varchar", "CHARACTER_MAXIMUM_LENGTH", 32,
                        "NUMERIC_PRECISION", null, "NUMERIC_SCALE", null,
                        "IS_NULLABLE", "NO", "COLUMN_KEY", "", "COLUMN_COMMENT", null)
        ));
        when(conn.prepareStatement(anyString())).thenReturn(tablesPs, colsPs);
        when(tablesPs.executeQuery()).thenReturn(tablesRs);
        when(colsPs.executeQuery()).thenReturn(colsRs);
        DataSourceContext context = contextWith(config(), conn);
        when(registry.resolve("ds-mysql")).thenReturn(context);

        List<TableMeta> tables = provider.listTables(
                MetadataRequest.builder().dsId("ds-mysql").includeColumns(true).includeStats(false).build());

        assertThat(tables).hasSize(1);
        List<ColumnMeta> columns = tables.get(0).getColumns();
        assertThat(columns).hasSize(2);
        assertThat(columns.get(0).getColumnName()).isEqualTo("amount");
        assertThat(columns.get(0).getNumericPrecision()).isEqualTo(12);
        assertThat(columns.get(0).getNumericScale()).isEqualTo(4);
        assertThat(columns.get(1).getColumnName()).isEqualTo("code");
        assertThat(columns.get(1).getColumnSize()).isEqualTo(32);
        assertThat(columns.get(1).getNumericPrecision()).isNull();
        assertThat(columns.get(1).getNumericScale()).isNull();
    }

    @Test
    @DisplayName("listColumns SQL 同时包含 NUMERIC_PRECISION/NUMERIC_SCALE 与 CHARACTER_MAXIMUM_LENGTH（参数化）")
    void should_selectNumericColumns_when_listColumns() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = ResultSetMocks.rows(List.of());
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        DataSourceContext context = contextWith(config(), conn);
        when(registry.resolve("ds-mysql")).thenReturn(context);

        assertThat(provider.listColumns(
                MetadataRequest.builder().dsId("ds-mysql").build(), "t_order")).isEmpty();

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(conn).prepareStatement(sql.capture());
        assertThat(sql.getValue())
                .contains("NUMERIC_PRECISION")
                .contains("NUMERIC_SCALE")
                .contains("CHARACTER_MAXIMUM_LENGTH")
                // SQL 仍为参数化（无字符串拼接注入面）
                .contains("TABLE_SCHEMA = ?")
                .contains("TABLE_NAME = ?");
    }
}
