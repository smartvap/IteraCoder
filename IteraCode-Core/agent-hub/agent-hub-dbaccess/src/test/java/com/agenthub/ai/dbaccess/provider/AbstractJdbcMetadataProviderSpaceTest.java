package com.agenthub.ai.dbaccess.provider;

import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.meta.RelationMeta;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多 schema（spaceNames）空间解析与越界拒绝核心单测。
 *
 * <p>直接覆盖 package-private 静态工具 {@code accessibleSpaces/isSpaceAllowed/resolveSchemaCandidatesCore}
 * 与 protected 实例方法 {@code resolveSpace}（同包访问），验证：集合解析、越界拒绝、空回落三类行为，
 * 不触碰真实 DB。</p>
 */
class AbstractJdbcMetadataProviderSpaceTest {

    // ------------------------------------------------------------------
    // accessibleSpaces：集合规范化（去空/去重/trim）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("accessibleSpaces 去空/trim/去重并保持顺序")
    void should_normalizeSpaceNames_when_mixed() {
        DsConfigView config = DsConfigView.builder()
                .spaceNames(Arrays.asList(" CRM_A ", "CRM_A", "", null, " CRM_B", "CRM_B", "CRM_A"))
                .build();
        assertThat(AbstractJdbcMetadataProvider.accessibleSpaces(config))
                .containsExactly("CRM_A", "CRM_B");
    }

    @Test
    @DisplayName("accessibleSpaces 未配置（null/空）返回空集合表示不限制")
    void should_empty_when_spaceNamesNotConfigured() {
        assertThat(AbstractJdbcMetadataProvider.accessibleSpaces(null)).isEmpty();
        assertThat(AbstractJdbcMetadataProvider.accessibleSpaces(DsConfigView.builder().build())).isEmpty();
    }

    // ------------------------------------------------------------------
    // resolveSchemaCandidatesCore：集合解析 / 越界拒绝 / 空回落
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveSchemaCandidates: request 未指定且 spaceNames 非空 → 返回整个集合")
    void should_returnSpaceNames_when_noRequestSpace() {
        DsConfigView config = DsConfigView.builder()
                .spaceName("CRM_A")
                .spaceNames(List.of("CRM_A", "CRM_B"))
                .build();
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(config, null))
                .containsExactly("CRM_A", "CRM_B");
    }

    @Test
    @DisplayName("resolveSchemaCandidates: request 指定集合内库 → 返回单元素")
    void should_returnSingleSpace_when_requestInCollection() {
        DsConfigView config = DsConfigView.builder().spaceNames(List.of("CRM_A", "CRM_B")).build();
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(config, "CRM_B"))
                .containsExactly("CRM_B");
    }

    @Test
    @DisplayName("resolveSchemaCandidates: request 指定集合外库 → 越界拒绝返回空清单")
    void should_reject_when_requestOutOfCollection() {
        DsConfigView config = DsConfigView.builder().spaceNames(List.of("CRM_A", "CRM_B")).build();
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(config, "CRM_X")).isEmpty();
    }

    @Test
    @DisplayName("resolveSchemaCandidates: spaceNames 空 + request 指定任意库 → 放行（不限制）")
    void should_allowAnySpace_when_noCollectionBoundary() {
        DsConfigView config = DsConfigView.builder().spaceName("default").build();
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(config, "ANY_DB"))
                .containsExactly("ANY_DB");
    }

    @Test
    @DisplayName("resolveSchemaCandidates: 空回落 [spaceName]（未配置 spaceNames 时单库）")
    void should_fallbackToSpaceName_when_noSpaceNames() {
        DsConfigView config = DsConfigView.builder().spaceName("  legacy_db  ").build();
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(config, null))
                .containsExactly("legacy_db");
    }

    @Test
    @DisplayName("resolveSchemaCandidates: 无 spaceNames 且 spaceName 为空 → 空清单（不可多库循环）")
    void should_empty_when_noSchemaConfigured() {
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(
                DsConfigView.builder().build(), null)).isEmpty();
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(null, null)).isEmpty();
    }

    // ------------------------------------------------------------------
    // resolveSpace：三级回落 + 越界拒绝
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveSpace: request 指定集合内库返回；指定集合外库返回 null")
    void should_resolveSpace_when_requestExplicit() throws Exception {
        TestProvider provider = new TestProvider();
        DsConfigView config = DsConfigView.builder().spaceNames(List.of("CRM_A", "CRM_B")).build();
        DataSourceContext context = DataSourceContext.builder().config(config).build();

        assertThat(provider.resolveSpace(mock(Connection.class), context,
                MetadataRequest.builder().dsId("ds").spaceName("CRM_A").build())).isEqualTo("CRM_A");
        // 越界显式库被拒
        assertThat(provider.resolveSpace(mock(Connection.class), context,
                MetadataRequest.builder().dsId("ds").spaceName("CRM_X").build())).isNull();
    }

    @Test
    @DisplayName("resolveSpace: 回落 conn catalog，catalog 在集合内返回、集合外拒绝")
    void should_reject_when_catalogOutOfBoundary() throws Exception {
        TestProvider provider = new TestProvider();
        DsConfigView config = DsConfigView.builder().spaceNames(List.of("CRM_A", "CRM_B")).build();
        DataSourceContext context = DataSourceContext.builder().config(config).build();

        Connection inBound = mock(Connection.class);
        when(inBound.getCatalog()).thenReturn("CRM_A");
        assertThat(provider.resolveSpace(inBound, context,
                MetadataRequest.builder().dsId("ds").build())).isEqualTo("CRM_A");

        Connection outBound = mock(Connection.class);
        when(outBound.getCatalog()).thenReturn("CRM_X");
        assertThat(provider.resolveSpace(outBound, context,
                MetadataRequest.builder().dsId("ds").build())).isNull();
    }

    @Test
    @DisplayName("resolveSpace: 未配置 spaceNames（旧单库）原逻辑不变，catalog 任意库放行")
    void should_keepLegacyBehavior_when_noBoundary() throws Exception {
        TestProvider provider = new TestProvider();
        DsConfigView config = DsConfigView.builder().spaceName("legacy_db").build();
        DataSourceContext context = DataSourceContext.builder().config(config).build();

        // 无 request：回落 config.spaceName
        assertThat(provider.resolveSpace(mock(Connection.class), context,
                MetadataRequest.builder().dsId("ds").build())).isEqualTo("legacy_db");

        // request 显式指定任意库放行（未设边界）
        assertThat(provider.resolveSpace(mock(Connection.class), context,
                MetadataRequest.builder().dsId("ds").spaceName("ANY").build())).isEqualTo("ANY");

        // config.spaceName 缺失：回落 catalog（不限制）
        DsConfigView noSpace = DsConfigView.builder().build();
        Connection conn = mock(Connection.class);
        when(conn.getCatalog()).thenReturn("cat_db");
        assertThat(provider.resolveSpace(conn, DataSourceContext.builder().config(noSpace).build(),
                MetadataRequest.builder().dsId("ds").build())).isEqualTo("cat_db");
    }

    @Test
    @DisplayName("resolveSpace: config.spaceName 非空但不在 spaceNames 集合 → 越界拒绝")
    void should_reject_when_configSpaceOutOfBoundary() throws Exception {
        TestProvider provider = new TestProvider();
        DsConfigView config = DsConfigView.builder()
                .spaceName("LEGACY_OUT")
                .spaceNames(List.of("CRM_A", "CRM_B"))
                .build();
        DataSourceContext context = DataSourceContext.builder().config(config).build();
        assertThat(provider.resolveSpace(mock(Connection.class), context,
                MetadataRequest.builder().dsId("ds").build())).isNull();
    }

    // ------------------------------------------------------------------
    // 修复回归（第 1 轮）：库集合边界大小写口径 —— Oracle 族归一、MySQL 族精确
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回归：Oracle 族 spaceNames=[\"crm\"] 与回落/请求的 \"CRM\" 视为同库（大小写归一放行）")
    void should_allowOracleFamily_when_spaceCaseDiffers() {
        DsConfigView config = DsConfigView.builder()
                .dbType("ORACLE")
                .spaceNames(List.of("crm"))
                .build();
        // request 指定大写 CRM：归一后命中小写 crm，放行且原样返回请求库
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(config, "CRM"))
                .containsExactly("CRM");
        // 反向：请求小写 crm，集合声明大写 CRM
        DsConfigView upper = DsConfigView.builder()
                .dbType("ORACLE")
                .spaceNames(List.of("CRM"))
                .build();
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(upper, "crm"))
                .containsExactly("crm");
    }

    @Test
    @DisplayName("回归：Oracle 族（含 OceanBase-Oracle）集合外库仍拒绝，不因归一而放大边界")
    void should_rejectOracleFamily_when_spaceTrulyOutOfBoundary() {
        DsConfigView config = DsConfigView.builder()
                .dbType("OCEANBASE_ORACLE")
                .spaceNames(List.of("crm"))
                .build();
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(config, "OTHER_DB"))
                .isEmpty();
    }

    @Test
    @DisplayName("回归：MySQL 族大小写敏感，spaceNames=[\"crm\"] 不命中请求 \"CRM\"（精确匹配不回归）")
    void should_rejectMysqlFamily_when_spaceCaseDiffers() {
        DsConfigView config = DsConfigView.builder()
                .dbType("MYSQL")
                .spaceNames(List.of("crm"))
                .build();
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(config, "CRM"))
                .isEmpty();
        // 精确匹配仍放行
        assertThat(AbstractJdbcMetadataProvider.resolveSchemaCandidatesCore(config, "crm"))
                .containsExactly("crm");
    }

    @Test
    @DisplayName("回归：resolveSpace 经库族口径比较 —— Oracle 族 catalog \"CRM\" 命中 spaceNames [\"crm\"]")
    void should_allowOracleFamilyCatalog_when_caseDiffers() throws Exception {
        TestProvider provider = new TestProvider();
        DataSourceContext context = DataSourceContext.builder()
                .config(DsConfigView.builder().dbType("ORACLE").spaceNames(List.of("crm")).build())
                .build();
        Connection conn = mock(Connection.class);
        when(conn.getCatalog()).thenReturn("CRM");

        assertThat(provider.resolveSpace(conn, context,
                MetadataRequest.builder().dsId("ds").build())).isEqualTo("CRM");
    }

    @Test
    @DisplayName("回归：resolveSpace MySQL 族 catalog \"CRM\" 不命中 spaceNames [\"crm\"] → 越界拒绝 null")
    void should_rejectMysqlFamilyCatalog_when_caseDiffers() throws Exception {
        TestProvider provider = new TestProvider();
        DataSourceContext context = DataSourceContext.builder()
                .config(DsConfigView.builder().dbType("MYSQL").spaceNames(List.of("crm")).build())
                .build();
        Connection conn = mock(Connection.class);
        when(conn.getCatalog()).thenReturn("CRM");

        assertThat(provider.resolveSpace(conn, context,
                MetadataRequest.builder().dsId("ds").build())).isNull();
    }

    // ------------------------------------------------------------------
    // resolveSpace 回落链扩展：request > ds.spaceName > catalog > schema > 库族默认
    // ------------------------------------------------------------------

    @Test
    @DisplayName("回落链：catalog 缺失 → 回落 conn.getSchema()")
    void should_fallbackToSchema_when_catalogAbsent() throws Exception {
        TestProvider provider = new TestProvider();
        DataSourceContext context = DataSourceContext.builder()
                .config(DsConfigView.builder().dbType("MYSQL").build()).build();
        Connection conn = mock(Connection.class);
        when(conn.getCatalog()).thenReturn(null);
        when(conn.getSchema()).thenReturn("schema_db");

        assertThat(provider.resolveSpace(conn, context,
                MetadataRequest.builder().dsId("ds").build())).isEqualTo("schema_db");
    }

    @Test
    @DisplayName("回落链：catalog/schema 均缺失 + Oracle 族 → 族默认 SELECT USER FROM DUAL")
    void should_fallbackToOracleFamilyDefault_when_noCatalogSchema() throws Exception {
        TestProvider provider = new TestProvider();
        DataSourceContext context = DataSourceContext.builder()
                .config(DsConfigView.builder().dbType("OCEANBASE_ORACLE").build()).build();
        Connection conn = mock(Connection.class);
        when(conn.getCatalog()).thenReturn(null);
        when(conn.getSchema()).thenReturn(null);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement("SELECT USER FROM DUAL")).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn("CRM");

        assertThat(provider.resolveSpace(conn, context,
                MetadataRequest.builder().dsId("ds").build())).isEqualTo("CRM");
    }

    @Test
    @DisplayName("回落链：MySQL 族无 catalog/schema → 最终 null（拒绝全库采集防泄露）")
    void should_returnNull_when_mysqlFamilyNoCatalogSchema() throws Exception {
        TestProvider provider = new TestProvider();
        DataSourceContext context = DataSourceContext.builder()
                .config(DsConfigView.builder().dbType("MYSQL").build()).build();
        Connection conn = mock(Connection.class);
        when(conn.getCatalog()).thenReturn(null);
        when(conn.getSchema()).thenReturn(null);

        assertThat(provider.resolveSpace(conn, context,
                MetadataRequest.builder().dsId("ds").build())).isNull();
    }

    @Test
    @DisplayName("回落链：Oracle 族默认探测失败（DUAL 不可用）→ null")
    void should_returnNull_when_oracleFamilyDefaultProbeFails() throws Exception {
        TestProvider provider = new TestProvider();
        DataSourceContext context = DataSourceContext.builder()
                .config(DsConfigView.builder().dbType("ORACLE").build()).build();
        Connection conn = mock(Connection.class);
        when(conn.getCatalog()).thenReturn(null);
        when(conn.getSchema()).thenReturn(null);
        when(conn.prepareStatement("SELECT USER FROM DUAL")).thenThrow(new SQLException("no dual"));

        assertThat(provider.resolveSpace(conn, context,
                MetadataRequest.builder().dsId("ds").build())).isNull();
    }

    @Test
    @DisplayName("回落链：族默认解析出的空间仍受 spaceNames 边界约束（越界拒绝）")
    void should_rejectOracleFamilyDefault_when_outOfBoundary() throws Exception {
        TestProvider provider = new TestProvider();
        DataSourceContext context = DataSourceContext.builder()
                .config(DsConfigView.builder().dbType("ORACLE").spaceNames(List.of("CRM_A")).build()).build();
        Connection conn = mock(Connection.class);
        when(conn.getCatalog()).thenReturn(null);
        when(conn.getSchema()).thenReturn(null);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(conn.prepareStatement("SELECT USER FROM DUAL")).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true);
        when(rs.getString(1)).thenReturn("OTHER_SCHEMA");

        assertThat(provider.resolveSpace(conn, context,
                MetadataRequest.builder().dsId("ds").build())).isNull();
    }

    // ------------------------------------------------------------------
    // resolveSchemaCandidates（protected 实例方法同包访问）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("resolveSchemaCandidates 实例方法透传 config + request")
    void should_resolveInstance_when_subclass() {
        TestProvider provider = new TestProvider();
        DsConfigView config = DsConfigView.builder().spaceNames(List.of("CRM_A", "CRM_B")).build();
        DataSourceContext context = DataSourceContext.builder().config(config).build();
        assertThat(provider.resolveSchemaCandidates(context,
                MetadataRequest.builder().dsId("ds").build())).containsExactly("CRM_A", "CRM_B");
        assertThat(provider.resolveSchemaCandidates(context,
                MetadataRequest.builder().dsId("ds").spaceName("CRM_X").build())).isEmpty();
    }

    // ------------------------------------------------------------------
    // listAllSchemas：库集合遍历便捷入口（逐库单 space 采集 + spaceName 赋值）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("listAllSchemas: spaceNames 多库时逐库调用 listTables 并合并，spaceName 为实际库")
    void should_traverseEachSpace_when_spaceNamesConfigured() {
        DsConfigView config = DsConfigView.builder().spaceNames(List.of("CRM_A", "CRM_B", "CRM_C")).build();
        DataSourceContext context = DataSourceContext.builder().config(config).build();
        TestProvider provider = new TestProvider(context);

        List<TableMeta> tables = provider.listAllSchemas(
                MetadataRequest.builder().dsId("ds").includeColumns(false).includeStats(false).build());

        // 每库产生一张表，且 TableMeta.spaceName 为该库实际 schema 名
        assertThat(tables).hasSize(3);
        assertThat(tables).extracting(TableMeta::getSpaceName)
                .containsExactly("CRM_A", "CRM_B", "CRM_C");
        assertThat(tables).extracting(TableMeta::getTableName)
                .containsExactly("T_CRM_A", "T_CRM_B", "T_CRM_C");
    }

    @Test
    @DisplayName("listAllSchemas: 未配置集合（spaceNames 空回落单库）→ 按默认单库语义返回")
    void should_fallbackToSingleSpace_when_noCollection() {
        DsConfigView config = DsConfigView.builder().spaceName("legacy_db").build();
        DataSourceContext context = DataSourceContext.builder().config(config).build();
        TestProvider provider = new TestProvider(context);

        List<TableMeta> tables = provider.listAllSchemas(
                MetadataRequest.builder().dsId("ds").includeColumns(false).includeStats(false).build());

        // 回落单 space 语义：candidates=[legacy_db]，同样逐库调用一次
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).getSpaceName()).isEqualTo("legacy_db");
    }

    /** 最小 Provider 测试子类（不触碰 registry/DB） */
    static class TestProvider extends AbstractJdbcMetadataProvider {

        private final DataSourceContext fixedContext;

        TestProvider() {
            this(null);
        }

        TestProvider(DataSourceContext fixedContext) {
            super(null, null);
            this.fixedContext = fixedContext;
        }

        @Override
        protected DataSourceContext resolveContext(MetadataRequest request) {
            if (fixedContext == null) {
                throw new IllegalStateException("测试子类未注入 context");
            }
            return fixedContext;
        }

        @Override
        public String providerName() {
            return "test";
        }

        @Override
        public boolean supports(DbType dbType) {
            return false;
        }

        @Override
        public List<TableMeta> listTables(MetadataRequest request) {
            String space = request.getSpaceName() == null ? "?" : request.getSpaceName();
            return List.of(TableMeta.builder()
                    .tableName("T_" + space)
                    .spaceName(space)
                    .dbType(DbType.MYSQL)
                    .provider("test")
                    .build());
        }

        @Override
        public List<ColumnMeta> listColumns(MetadataRequest request, String tableName) {
            return List.of();
        }

        @Override
        public List<RelationMeta> listRelations(MetadataRequest request, String tableName) {
            return List.of();
        }
    }
}
