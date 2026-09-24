package com.agenthub.ai.dbaccess.provider;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.meta.RelationMeta;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import com.agenthub.ai.dbaccess.model.DbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MetadataProviderRegistry Provider 自动发现与路由测试（含 fallback 兜底）。
 */
class MetadataProviderRegistryTest {

    @Test
    @DisplayName("providerOf 命中特定 Provider（不改调用方新增扩展）")
    void should_routeProvider_when_supported() {
        MetadataProviderRegistry registry = new MetadataProviderRegistry(
                List.of(new FakeMysqlProvider(), new FakeOracleProvider()),
                null);
        assertThat(registry.providerOf(DbType.MYSQL)).isInstanceOf(FakeMysqlProvider.class);
        assertThat(registry.providerOf(DbType.OCEANBASE_MYSQL)).isInstanceOf(FakeMysqlProvider.class);
        assertThat(registry.providerOf(DbType.ORACLE)).isInstanceOf(FakeOracleProvider.class);
    }

    @Test
    @DisplayName("无特定 Provider 时返回 JdbcFallback 兜底")
    void should_fallback_when_noSpecificProvider() {
        JdbcFallbackProvider fallback = mock(JdbcFallbackProvider.class);
        when(fallback.supports(any())).thenReturn(true);
        MetadataProviderRegistry registry = new MetadataProviderRegistry(List.of(), fallback);
        assertThat(registry.providerOf(DbType.MYSQL)).isSameAs(fallback);
        assertThat(registry.providerOf(DbType.ORACLE)).isSameAs(fallback);
    }

    @Test
    @DisplayName("null dbType 或无可用 Provider 抛 NO_PROVIDER")
    void should_throwNoProvider_when_unavailable() {
        MetadataProviderRegistry registry = new MetadataProviderRegistry(List.of(), null);
        assertThatThrownBy(() -> registry.providerOf(null))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.NO_PROVIDER.getCode()));
        assertThatThrownBy(() -> registry.providerOf(DbType.MYSQL))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.NO_PROVIDER.getCode()));
    }

    @Test
    @DisplayName("MySqlProvider supports 仅覆盖 MYSQL 族")
    void should_supportMysqlFamily_when_mySqlProvider() {
        // supports 无需真实 DB
        assertThat(DbType.MYSQL.isMysqlFamily()).isTrue();
        assertThat(DbType.ORACLE.isMysqlFamily()).isFalse();
    }

    /** 假 MySQL Provider（用于注册表路由测试，不触碰 DB） */
    static class FakeMysqlProvider implements MetadataProvider {
        @Override
        public String providerName() {
            return "fake-mysql";
        }

        @Override
        public boolean supports(DbType dbType) {
            return dbType != null && dbType.isMysqlFamily();
        }

        @Override
        public List<TableMeta> listTables(MetadataRequest request) {
            return List.of();
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

    /** 假 Oracle Provider */
    static class FakeOracleProvider implements MetadataProvider {
        @Override
        public String providerName() {
            return "fake-oracle";
        }

        @Override
        public boolean supports(DbType dbType) {
            return dbType != null && dbType.isOracleFamily();
        }

        @Override
        public List<TableMeta> listTables(MetadataRequest request) {
            return List.of();
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
