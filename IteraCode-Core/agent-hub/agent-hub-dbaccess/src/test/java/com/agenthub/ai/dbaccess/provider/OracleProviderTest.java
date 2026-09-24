package com.agenthub.ai.dbaccess.provider;

import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.meta.RelationMeta;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.model.SensitiveFieldRule;
import com.agenthub.ai.dbaccess.model.SensitiveLevel;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.dbaccess.security.SensitiveFieldRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OracleProvider 真实实现采集语义单测（可单测部分，不依赖真实 Oracle 库）。
 *
 * <p>以 mock Connection/PreparedStatement 验证：Oracle 族归口、OWNER 大写归一、
 * SQL 全参数化（? 绑定）、列/关系中立模型映射、库集合越界早退。真实 SQL 语义/字典视图
 * 由联调（SC-005）覆盖，单测仅锁定可离线校验的采集契约。</p>
 */
class OracleProviderTest {

    private DataSourceRegistry registry;
    private SensitiveFieldRegistry sensitiveFieldRegistry;
    private OracleProvider provider;

    @BeforeEach
    void setUp() {
        registry = mock(DataSourceRegistry.class);
        sensitiveFieldRegistry = mock(SensitiveFieldRegistry.class);
        when(sensitiveFieldRegistry.loadByDs(anyString())).thenReturn(List.of());
        provider = new OracleProvider(registry, sensitiveFieldRegistry);
    }

    private DataSourceContext contextWith(DsConfigView config, Connection conn) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(conn);
        return DataSourceContext.builder().config(config).dataSource(dataSource).build();
    }

    private DsConfigView config(String spaceName, List<String> spaceNames) {
        return DsConfigView.builder()
                .dsId("ds-ora")
                .dbType("ORACLE")
                .spaceName(spaceName)
                .spaceNames(spaceNames)
                .build();
    }

    @Test
    @DisplayName("supports/providerName：Oracle 族（ORACLE/OCEANBASE_ORACLE）归口本实现")
    void should_supportOracleFamily_when_ask() {
        assertThat(provider.providerName()).isEqualTo("oracle");
        assertThat(provider.supports(DbType.ORACLE)).isTrue();
        assertThat(provider.supports(DbType.OCEANBASE_ORACLE)).isTrue();
        assertThat(provider.supports(DbType.MYSQL)).isFalse();
        assertThat(provider.supports(DbType.OCEANBASE_MYSQL)).isFalse();
    }

    @Test
    @DisplayName("listTables：OWNER 大写归一 + 参数化查询 + 回填 provider/spaceName/注释")
    void should_listTables_when_oracle() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("TABLE_NAME")).thenReturn("CUSTOMER");
        when(rs.getString("TABLE_TYPE")).thenReturn("TABLE");
        when(rs.getString("TABLE_COMMENT")).thenReturn("客户表");

        DataSourceContext context = contextWith(config("crm", null), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<TableMeta> tables = provider.listTables(
                MetadataRequest.builder().dsId("ds-ora").includeColumns(false).build());

        assertThat(tables).hasSize(1);
        TableMeta table = tables.get(0);
        assertThat(table.getTableName()).isEqualTo("CUSTOMER");
        assertThat(table.getSpaceName()).isEqualTo("CRM"); // OWNER 大写归一
        assertThat(table.getTableType()).isEqualTo("TABLE");
        assertThat(table.getTableComment()).isEqualTo("客户表");
        assertThat(table.getProvider()).isEqualTo("oracle");
        assertThat(table.getDbType()).isEqualTo(DbType.ORACLE);
        verify(ps).setString(1, "CRM");
    }

    @Test
    @DisplayName("listTables：request 指定集合外库 → 早退空清单，不发起采集查询")
    void should_returnEmpty_when_spaceOutOfBoundary() throws Exception {
        Connection conn = mock(Connection.class);
        DataSourceContext context = contextWith(config(null, List.of("CRM_A")), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<TableMeta> tables = provider.listTables(
                MetadataRequest.builder().dsId("ds-ora").spaceName("OTHER").build());

        assertThat(tables).isEmpty();
        verify(conn, never()).prepareStatement(anyString());
    }

    @Test
    @DisplayName("listColumns：主键标记 + 列长度/可空/注释映射 + 参数化（OWNER/表名）")
    void should_listColumns_when_oracle() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement pkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet pkRs = mock(ResultSet.class);
        ResultSet colRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(pkPs, colPs);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(true, false);
        when(pkRs.getString("COLUMN_NAME")).thenReturn("ID");
        when(colPs.executeQuery()).thenReturn(colRs);
        when(colRs.next()).thenReturn(true, false);
        when(colRs.getString("COLUMN_NAME")).thenReturn("ID");
        when(colRs.getInt("COLUMN_ID")).thenReturn(1);
        when(colRs.getString("DATA_TYPE")).thenReturn("NUMBER");
        when(colRs.getInt("CHAR_LENGTH")).thenReturn(0);
        when(colRs.getInt("DATA_LENGTH")).thenReturn(22);
        when(colRs.getString("NULLABLE")).thenReturn("N");
        when(colRs.getString("COLUMN_COMMENT")).thenReturn("主键");

        DataSourceContext context = contextWith(config("crm", null), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-ora").build(), "CUSTOMER");

        assertThat(columns).hasSize(1);
        ColumnMeta column = columns.get(0);
        assertThat(column.getColumnName()).isEqualTo("ID");
        assertThat(column.getTableName()).isEqualTo("CUSTOMER");
        assertThat(column.getOrdinal()).isEqualTo(1);
        assertThat(column.getDataType()).isEqualTo("NUMBER");
        assertThat(column.getColumnSize()).isEqualTo(22);
        assertThat(column.getNullable()).isZero();
        assertThat(column.getIsPrimaryKey()).isEqualTo(1);
        assertThat(column.getColumnComment()).isEqualTo("主键");
        verify(colPs).setString(1, "CRM");
        verify(colPs).setString(2, "CUSTOMER");
    }

    @Test
    @DisplayName("listRelations：外键关系映射 + 参数化（OWNER/表名）")
    void should_listRelations_when_oracle() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("source_table")).thenReturn("ORDERS");
        when(rs.getString("source_column")).thenReturn("CUST_ID");
        when(rs.getString("target_table")).thenReturn("CUSTOMER");
        when(rs.getString("target_column")).thenReturn("ID");
        when(rs.getString("constraint_name")).thenReturn("FK_ORDERS_CUST");

        DataSourceContext context = contextWith(config("crm", null), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<RelationMeta> relations = provider.listRelations(
                MetadataRequest.builder().dsId("ds-ora").build(), "ORDERS");

        assertThat(relations).hasSize(1);
        RelationMeta relation = relations.get(0);
        assertThat(relation.getSourceTable()).isEqualTo("ORDERS");
        assertThat(relation.getSourceColumn()).isEqualTo("CUST_ID");
        assertThat(relation.getTargetTable()).isEqualTo("CUSTOMER");
        assertThat(relation.getTargetColumn()).isEqualTo("ID");
        assertThat(relation.getRelationType()).isEqualTo("FK");
        assertThat(relation.getConstraintName()).isEqualTo("FK_ORDERS_CUST");
        verify(ps).setString(1, "CRM");
        verify(ps).setString(2, "ORDERS");
    }

    @Test
    @DisplayName("回归：listColumns 传入小写表名 customer → 归一为 CUSTOMER 后绑定（主键+列两次查询一致）")
    void should_normalizeLowercaseTableName_when_listColumns() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement pkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet pkRs = mock(ResultSet.class);
        ResultSet colRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(pkPs, colPs);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(colPs.executeQuery()).thenReturn(colRs);
        when(colRs.next()).thenReturn(false);

        DataSourceContext context = contextWith(config("crm", null), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-ora").build(), "customer");

        assertThat(columns).isEmpty();
        // OWNER 与表名同口径大写归一，避免字典查询静默返回空
        verify(pkPs).setString(1, "CRM");
        verify(pkPs).setString(2, "CUSTOMER");
        verify(colPs).setString(1, "CRM");
        verify(colPs).setString(2, "CUSTOMER");
    }

    @Test
    @DisplayName("回归：listColumns includeStats 采样表引用使用归一（大写）表名 \"CRM\".\"CUSTOMER\"，非小写 customer")
    void should_useNormalizedTableName_when_sampleColumnsWithStats() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement pkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        PreparedStatement samplePs = mock(PreparedStatement.class);
        ResultSet pkRs = mock(ResultSet.class);
        ResultSet colRs = mock(ResultSet.class);
        ResultSet sampleRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(pkPs, colPs, samplePs);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        when(colPs.executeQuery()).thenReturn(colRs);
        when(colRs.next()).thenReturn(true, false);
        when(colRs.getString("COLUMN_NAME")).thenReturn("ID");
        when(colRs.getInt("COLUMN_ID")).thenReturn(1);
        when(colRs.getString("DATA_TYPE")).thenReturn("NUMBER");
        when(colRs.getInt("CHAR_LENGTH")).thenReturn(22);
        when(colRs.getString("NULLABLE")).thenReturn("N");
        when(colRs.getString("COLUMN_COMMENT")).thenReturn("主键");
        when(samplePs.executeQuery()).thenReturn(sampleRs);
        when(sampleRs.next()).thenReturn(true, false);
        when(sampleRs.getObject(1)).thenReturn("13800000000");
        // 命中敏感规则（表/列大小写不敏感）→ fillColumnSamples 真正发起单行采样
        when(sensitiveFieldRegistry.loadByDs("ds-ora")).thenReturn(List.of(
                SensitiveFieldRule.builder().dsId("ds-ora").table("CUSTOMER").column("ID")
                        .level(SensitiveLevel.HIGH).build()));
        when(sensitiveFieldRegistry.mask(anyString(), any())).thenReturn("***");

        DataSourceContext context = contextWith(config("crm", null), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-ora").includeStats(true).build(), "customer");

        assertThat(columns).hasSize(1);
        // 三次 prepareStatement：单表主键 → 单表列字典 → 采样；第 3 条即采样 SQL
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(conn, times(3)).prepareStatement(sqlCaptor.capture());
        String sampleSql = sqlCaptor.getAllValues().get(2);
        // Oracle 双引号标识符大小写敏感：采样表引用必须用归一后大写 tableName，否则 ORA-00942 被静默吞掉
        assertThat(sampleSql).isEqualTo("SELECT \"ID\" FROM \"CRM\".\"CUSTOMER\"");
        assertThat(sampleSql).doesNotContain("\"customer\"");
        verify(samplePs).executeQuery();
        assertThat(columns.get(0).getSampleValue()).isEqualTo("***");
    }

    @Test
    @DisplayName("回归：listRelations 传入小写表名 orders → 归一为 ORDERS 后绑定")
    void should_normalizeLowercaseTableName_when_listRelations() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        DataSourceContext context = contextWith(config("crm", null), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<RelationMeta> relations = provider.listRelations(
                MetadataRequest.builder().dsId("ds-ora").build(), "orders");

        assertThat(relations).isEmpty();
        verify(ps).setString(1, "CRM");
        verify(ps).setString(2, "ORDERS");
    }

    @Test
    @DisplayName("listColumns：数值列填充 DATA_PRECISION/DATA_SCALE，非数值列精度/标度为 null，columnSize 语义不变")
    void should_fillNumericPrecisionAndScale_when_oracleColumns() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement pkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet pkRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(pkPs, colPs);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        ResultSet colRs = ResultSetMocks.rows(List.of(
                // AMT NUMBER(12,4)：CHAR_LENGTH 为 NULL → columnSize 落 DATA_LENGTH
                ResultSetMocks.row("COLUMN_NAME", "AMT", "COLUMN_ID", 1, "DATA_TYPE", "NUMBER",
                        "DATA_LENGTH", 22, "CHAR_LENGTH", null,
                        "DATA_PRECISION", 12, "DATA_SCALE", 4, "NULLABLE", "Y", "COLUMN_COMMENT", "金额"),
                // CODE VARCHAR2(32)：CHAR_LENGTH 优先；DATA_PRECISION/DATA_SCALE 为 NULL
                ResultSetMocks.row("COLUMN_NAME", "CODE", "COLUMN_ID", 2, "DATA_TYPE", "VARCHAR2",
                        "DATA_LENGTH", 32, "CHAR_LENGTH", 32,
                        "DATA_PRECISION", null, "DATA_SCALE", null, "NULLABLE", "N", "COLUMN_COMMENT", "编码")
        ));
        when(colPs.executeQuery()).thenReturn(colRs);

        DataSourceContext context = contextWith(config("crm", null), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-ora").build(), "CUSTOMER");

        assertThat(columns).hasSize(2);
        ColumnMeta amt = columns.get(0);
        assertThat(amt.getNumericPrecision()).isEqualTo(12);
        assertThat(amt.getNumericScale()).isEqualTo(4);
        assertThat(amt.getColumnSize()).isEqualTo(22);

        ColumnMeta code = columns.get(1);
        assertThat(code.getColumnSize()).isEqualTo(32);
        assertThat(code.getNumericPrecision()).isNull();
        assertThat(code.getNumericScale()).isNull();
    }

    @Test
    @DisplayName("listColumns：DATA_SCALE=0（合法标度）保留为 0，与 NULL 区分")
    void should_keepZeroScale_when_dataScaleZero() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement pkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet pkRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(pkPs, colPs);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        ResultSet colRs = ResultSetMocks.rows(List.of(
                ResultSetMocks.row("COLUMN_NAME", "AMT", "COLUMN_ID", 1, "DATA_TYPE", "NUMBER",
                        "DATA_LENGTH", 22, "CHAR_LENGTH", null,
                        "DATA_PRECISION", 10, "DATA_SCALE", 0, "NULLABLE", "Y", "COLUMN_COMMENT", null)
        ));
        when(colPs.executeQuery()).thenReturn(colRs);
        DataSourceContext context = contextWith(config("crm", null), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        List<ColumnMeta> columns = provider.listColumns(
                MetadataRequest.builder().dsId("ds-ora").build(), "CUSTOMER");

        assertThat(columns).hasSize(1);
        assertThat(columns.get(0).getNumericPrecision()).isEqualTo(10);
        assertThat(columns.get(0).getNumericScale()).isZero();
    }

    @Test
    @DisplayName("listColumns SQL 含 DATA_PRECISION/DATA_SCALE 与 CHAR_LENGTH/DATA_LENGTH（参数化不变）")
    void should_selectNumericColumns_when_listColumns() throws Exception {
        Connection conn = mock(Connection.class);
        PreparedStatement pkPs = mock(PreparedStatement.class);
        PreparedStatement colPs = mock(PreparedStatement.class);
        ResultSet pkRs = mock(ResultSet.class);
        when(conn.prepareStatement(anyString())).thenReturn(pkPs, colPs);
        when(pkPs.executeQuery()).thenReturn(pkRs);
        when(pkRs.next()).thenReturn(false);
        ResultSet colRs = ResultSetMocks.rows(List.of());
        when(colPs.executeQuery()).thenReturn(colRs);
        DataSourceContext context = contextWith(config("crm", null), conn);
        when(registry.resolve("ds-ora")).thenReturn(context);

        assertThat(provider.listColumns(
                MetadataRequest.builder().dsId("ds-ora").build(), "CUSTOMER")).isEmpty();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(conn, times(2)).prepareStatement(sqlCaptor.capture());
        String columnSql = sqlCaptor.getAllValues().get(1);
        assertThat(columnSql)
                .contains("c.DATA_PRECISION")
                .contains("c.DATA_SCALE")
                .contains("c.CHAR_LENGTH")
                .contains("c.DATA_LENGTH")
                .contains("c.OWNER = ?")
                .contains("c.TABLE_NAME = ?");
    }

    @Test
    @DisplayName("listColumns/listRelations：表名为空抛参数异常")
    void should_throw_when_tableNameBlank() {
        assertThatThrownBy(() -> provider.listColumns(
                MetadataRequest.builder().dsId("ds-ora").build(), "  "))
                .isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> provider.listRelations(
                MetadataRequest.builder().dsId("ds-ora").build(), null))
                .isInstanceOf(DbAccessException.class);
    }
}
