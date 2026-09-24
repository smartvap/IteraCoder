package com.agenthub.ai.dbaccess.provider;

import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.dbaccess.security.SensitiveFieldRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JdbcFallbackProvider 列读取单测：验证 DatabaseMetaData.getColumns 的
 * {@code COLUMN_SIZE → numericPrecision}、{@code DECIMAL_DIGITS → numericScale} 填充（两个 catalog/schema 分支），
 * NULL 严格为 null、标度 0 保留为 0，且不新增 ResultSetMetaData 探测往返。
 */
class JdbcFallbackProviderTest {

    private DataSourceRegistry registry;
    private SensitiveFieldRegistry sensitiveFieldRegistry;
    private JdbcFallbackProvider provider;

    @BeforeEach
    void setUp() {
        registry = mock(DataSourceRegistry.class);
        sensitiveFieldRegistry = mock(SensitiveFieldRegistry.class);
        when(sensitiveFieldRegistry.loadByDs(anyString())).thenReturn(List.of());
        provider = new JdbcFallbackProvider(registry, sensitiveFieldRegistry);
    }

    private DataSourceContext contextWith(DsConfigView config, Connection conn) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(conn);
        return DataSourceContext.builder().config(config).dataSource(dataSource).build();
    }

    private DsConfigView config() {
        return DsConfigView.builder()
                .dsId("ds-fb")
                .dbType("POSTGRESQL")
                .spaceName("testdb")
                .build();
    }

    private static List<java.util.Map<String, Object>> sampleColumns() {
        return List.of(
                // DECIMAL(12,4)
                ResultSetMocks.row("COLUMN_NAME", "AMOUNT", "ORDINAL_POSITION", 1, "TYPE_NAME", "DECIMAL",
                        "COLUMN_SIZE", 12, "DECIMAL_DIGITS", 4, "NULLABLE", 1, "REMARKS", "金额"),
                // VARCHAR(64)：COLUMN_SIZE 即字符长度（Fallback 口径下同时作为 numericPrecision 的取值来源）
                ResultSetMocks.row("COLUMN_NAME", "NAME", "ORDINAL_POSITION", 2, "TYPE_NAME", "VARCHAR",
                        "COLUMN_SIZE", 64, "DECIMAL_DIGITS", null, "NULLABLE", 1, "REMARKS", "名称"),
                // DATE：COLUMN_SIZE / DECIMAL_DIGITS 均为 NULL
                ResultSetMocks.row("COLUMN_NAME", "STATUS", "ORDINAL_POSITION", 3, "TYPE_NAME", "DATE",
                        "COLUMN_SIZE", null, "DECIMAL_DIGITS", null, "NULLABLE", 0, "REMARKS", null)
        );
    }

    @Test
    @DisplayName("listColumns（catalog 分支）：COLUMN_SIZE→numericPrecision、DECIMAL_DIGITS→numericScale，NULL 严格为 null")
    void should_fillNumericMeta_when_catalogBranch() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(meta);
        ResultSet pkRs = mock(ResultSet.class);
        when(meta.getPrimaryKeys(null, "testdb", "t_order")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        ResultSet colRs = ResultSetMocks.rows(sampleColumns());
        when(meta.getColumns(null, "testdb", "t_order", "%")).thenReturn(colRs);
        DataSourceContext context = contextWith(config(), conn);
        when(registry.resolve("ds-fb")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-fb").build(), "t_order");

        assertThat(columns).hasSize(3);

        ColumnMeta amount = columns.get(0);
        assertThat(amount.getNumericPrecision()).isEqualTo(12);
        assertThat(amount.getNumericScale()).isEqualTo(4);

        ColumnMeta name = columns.get(1);
        // FR-BE-004：Fallback 两分支均以 COLUMN_SIZE 填充 numericPrecision（VARCHAR 时为字符长度）
        assertThat(name.getNumericPrecision()).isEqualTo(64);
        // DECIMAL_DIGITS 为 NULL → 必须为 null（不是 0）
        assertThat(name.getNumericScale()).isNull();

        ColumnMeta status = columns.get(2);
        assertThat(status.getNumericPrecision()).isNull();
        assertThat(status.getNumericScale()).isNull();
        assertThat(status.getNullable()).isZero();

        // 不新增 ResultSetMetaData 探测：仅 getColumns 一次，无额外语句往返
        verify(conn, never()).prepareStatement(anyString());
        verify(meta).getColumns(null, "testdb", "t_order", "%");
    }

    @Test
    @DisplayName("listColumns（schema 分支）：catalog 调用抛异常后按 schema 重试，精度/标度同口径填充")
    void should_fillNumericMeta_when_schemaBranch() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(meta);
        ResultSet pkRs = mock(ResultSet.class);
        when(meta.getPrimaryKeys(null, "testdb", "t_order")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        // catalog 维度不受支持 → 触发 schema 维度重试分支
        when(meta.getColumns(null, "testdb", "t_order", "%")).thenThrow(new SQLException("catalog unsupported"));
        ResultSet colRs = ResultSetMocks.rows(sampleColumns());
        when(meta.getColumns("testdb", null, "t_order", "%")).thenReturn(colRs);
        DataSourceContext context = contextWith(config(), conn);
        when(registry.resolve("ds-fb")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-fb").build(), "t_order");

        assertThat(columns).hasSize(3);
        assertThat(columns.get(0).getNumericPrecision()).isEqualTo(12);
        assertThat(columns.get(0).getNumericScale()).isEqualTo(4);
        assertThat(columns.get(1).getNumericPrecision()).isEqualTo(64);
        assertThat(columns.get(1).getNumericScale()).isNull();
        assertThat(columns.get(2).getNumericPrecision()).isNull();
        assertThat(columns.get(2).getNumericScale()).isNull();

        verify(meta).getColumns(null, "testdb", "t_order", "%");
        verify(meta).getColumns("testdb", null, "t_order", "%");
    }

    @Test
    @DisplayName("listColumns：DECIMAL_DIGITS=0（合法标度）保留为 0，与 NULL 区分")
    void should_keepZeroScale_when_decimalDigitsZero() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(meta);
        ResultSet pkRs = mock(ResultSet.class);
        when(meta.getPrimaryKeys(null, "testdb", "t_order")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("COLUMN_NAME", "QTY", "ORDINAL_POSITION", 1, "TYPE_NAME", "INTEGER",
                        "COLUMN_SIZE", 10, "DECIMAL_DIGITS", 0, "NULLABLE", 1, "REMARKS", null)
        ));
        when(meta.getColumns(null, "testdb", "t_order", "%")).thenReturn(colRs);
        DataSourceContext context = contextWith(config(), conn);
        when(registry.resolve("ds-fb")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-fb").build(), "t_order");

        assertThat(columns).hasSize(1);
        assertThat(columns.get(0).getNumericPrecision()).isEqualTo(10);
        assertThat(columns.get(0).getNumericScale()).isZero();
    }

    @Test
    @DisplayName("listColumns：既有 columnSize（COLUMN_SIZE）语义不变，数值列实测受既有判定顺序影响为 0")
    void should_keepColumnSizeSemantics_when_nullableColumnSize() throws Exception {
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(conn.getMetaData()).thenReturn(meta);
        ResultSet pkRs = mock(ResultSet.class);
        when(meta.getPrimaryKeys(null, "testdb", "t_order")).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("COLUMN_NAME", "STATUS", "ORDINAL_POSITION", 1, "TYPE_NAME", "DATE",
                        "COLUMN_SIZE", null, "DECIMAL_DIGITS", null, "NULLABLE", 1, "REMARKS", null)
        ));
        when(meta.getColumns(null, "testdb", "t_order", "%")).thenReturn(colRs);
        DataSourceContext context = contextWith(config(), conn);
        when(registry.resolve("ds-fb")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-fb").build(), "t_order");

        assertThat(columns).hasSize(1);
        // 既有实现：columnSize 的 null 判定发生在 TYPE_NAME 读取之后（见 listColumns0 参数求值顺序），
        // COLUMN_SIZE 为 NULL 的列实测得到 0 而非 null；该行 T001 未触碰，行为改动前后一致。
        assertThat(columns.get(0).getColumnSize()).isZero();
        assertThat(columns.get(0).getNumericPrecision()).isNull();
        assertThat(columns.get(0).getNumericScale()).isNull();
    }

    @Test
    @DisplayName("listColumns：表名为空抛参数异常，不触碰连接")
    void should_throw_when_tableNameBlank() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.listColumns(
                        MetadataRequest.builder().dsId("ds-fb").build(), "  "))
                .isInstanceOf(com.agenthub.ai.dbaccess.exception.DbAccessException.class);
    }
}
