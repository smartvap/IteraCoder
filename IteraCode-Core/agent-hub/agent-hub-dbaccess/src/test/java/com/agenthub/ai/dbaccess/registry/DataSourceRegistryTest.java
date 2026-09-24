package com.agenthub.ai.dbaccess.registry;

import com.agenthub.ai.dbaccess.config.DbAccessProperties;
import com.agenthub.ai.dbaccess.constant.DbAccessConstants;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfig;
import com.agenthub.ai.dbaccess.model.DsConfigSpec;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.model.DsStatus;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DataSourceRegistry 路由/注册校验测试（依赖 mock，不触碰真实连接池）。
 */
class DataSourceRegistryTest {

    private final com.agenthub.ai.dbaccess.store.DsConfigStore store =
            mock(com.agenthub.ai.dbaccess.store.DsConfigStore.class);
    private final ReadonlyDataSourceFactory factory = mock(ReadonlyDataSourceFactory.class);
    private final com.agenthub.ai.dbaccess.dialect.DialectManager dialectManager =
            new com.agenthub.ai.dbaccess.dialect.DialectManager();
    private final DbAccessProperties properties = new DbAccessProperties();
    private final DataSourceRegistry registry =
            new DataSourceRegistry(store, factory, dialectManager, properties);

    @Test
    @DisplayName("resolve 未知数据源抛 UNKNOWN_DS")
    void should_throwUnknown_when_notFound() {
        when(store.findByDsId("nope")).thenReturn(null);
        assertThatThrownBy(() -> registry.resolve("nope"))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.UNKNOWN_DS.getCode()));
    }

    @Test
    @DisplayName("resolve 停用数据源抛 DS_DISABLED")
    void should_throwDisabled_when_statusZero() {
        DsConfig disabled = DsConfig.builder().dsId("ds-off").status(0).isDeleted(0).build();
        when(store.findByDsId("ds-off")).thenReturn(disabled);
        assertThatThrownBy(() -> registry.resolve("ds-off"))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.DS_DISABLED.getCode()));
    }

    @Test
    @DisplayName("resolve 空/非法 ds_id 抛 PARAM_INVALID")
    void should_throwParam_when_dsIdInvalid() {
        assertThatThrownBy(() -> registry.resolve("")).isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
        assertThatThrownBy(() -> registry.resolve("Bad_ID")).isInstanceOf(DbAccessException.class);
        assertThatThrownBy(() -> registry.resolve(null)).isInstanceOf(DbAccessException.class);
    }

    @Test
    @DisplayName("resolve 启用数据源返回上下文（方言+限制叠加）")
    void should_resolve_when_enabled() {
        DsConfig config = DsConfig.builder()
                .dsId("ds-ok").dsName("n").dbType(DbType.MYSQL)
                .status(1).isDeleted(0)
                .limits(com.agenthub.ai.dbaccess.model.ResourceLimits.builder().maxRows(500).build())
                .build();
        when(store.findByDsId("ds-ok")).thenReturn(config);
        when(factory.create(config)).thenReturn(new com.alibaba.druid.pool.DruidDataSource());

        DataSourceContext ctx = registry.resolve("ds-ok");
        assertThat(ctx.getConfig().getDsId()).isEqualTo("ds-ok");
        assertThat(ctx.getLimits().getMaxRows()).isEqualTo(500);
        assertThat(ctx.getLimits().getMaxSeconds()).isEqualTo(10); // 回落模块默认
        assertThat(ctx.getDialect()).isNotNull();
    }

    @Test
    @DisplayName("getConfig 未知数据源抛 UNKNOWN_DS，已知返回脱敏视图")
    void should_getConfig_when_found() {
        when(store.findByDsId("ds1")).thenReturn(null);
        assertThatThrownBy(() -> registry.getConfig("ds1"))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.UNKNOWN_DS.getCode()));

        DsConfig config = DsConfig.builder().dsId("ds1").dsName("n").dbType(DbType.MYSQL)
                .readonlyPasswordRef("${DBACCESS_DS_DS1_PWD}")
                .spaceNames(List.of("CRM_A", "CRM_B"))
                .status(1).isDeleted(0).build();
        when(store.findByDsId("ds1")).thenReturn(config);
        DsConfigView view = registry.getConfig("ds1");
        assertThat(view.getDsId()).isEqualTo("ds1");
        // 视图只含引用不含解析后的密码
        assertThat(view.getReadonlyPasswordRef()).isEqualTo("${DBACCESS_DS_DS1_PWD}");
        // spaceNames 可访问库集合透传至脱敏视图
        assertThat(view.getSpaceNames()).containsExactly("CRM_A", "CRM_B");
    }

    @Test
    @DisplayName("register 拒绝内嵌账密 jdbc_url")
    void should_reject_when_jdbcUrlEmbeddedCredential() {
        DsConfigSpec spec = spec("ds1")
                .jdbcUrl("jdbc:mysql://root:pwd123@localhost:3306/db")
                .build();
        assertThatThrownBy(() -> registry.register(spec))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> {
                    DbAccessException ex = (DbAccessException) e;
                    assertThat(ex.getCode()).isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode());
                    assertThat(ex.getMessage()).doesNotContain("pwd123");
                });
    }

    @Test
    @DisplayName("register 拒绝明文密码（passwordRef 非 ${ENV}）")
    void should_reject_when_plainPassword() {
        DsConfigSpec spec = spec("ds1")
                .readonlyPasswordRef("secret")
                .build();
        assertThatThrownBy(() -> registry.register(spec))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
    }

    @Test
    @DisplayName("register 拒绝 limits 覆盖超过模块默认上限（模块默认=硬上界）")
    void should_reject_when_limitsOverrideExceedsModule() {
        DsConfigSpec spec = spec("ds1")
                .limits(com.agenthub.ai.dbaccess.model.ResourceLimits.builder().maxRows(2000).build())
                .build();
        assertThatThrownBy(() -> registry.register(spec))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
    }

    @Test
    @DisplayName("register 拒绝 limits 覆盖 maxSeconds 超过模块默认上限")
    void should_reject_when_limitsSecondsExceedsModule() {
        DsConfigSpec spec = spec("ds1")
                .limits(com.agenthub.ai.dbaccess.model.ResourceLimits.builder().maxSeconds(60).build())
                .build();
        assertThatThrownBy(() -> registry.register(spec))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
    }

    @Test
    @DisplayName("register 缺 db_type / readonly_user 抛 PARAM_INVALID")
    void should_reject_when_missingRequired() {
        assertThatThrownBy(() -> registry.register(spec("ds1").dbType(null).build()))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
        assertThatThrownBy(() -> registry.register(spec("ds1").readonlyUser(null).build()))
                .isInstanceOf(DbAccessException.class);
    }

    @Test
    @DisplayName("register 合法 spec 返回 dsId 并落存储")
    void should_register_when_validSpec() {
        DsConfigSpec spec = spec("ds-ok").build();
        DsConfig saved = DsConfig.builder().dsId("ds-ok").dsName("n").dbType(DbType.MYSQL)
                .driverClass(DbType.MYSQL.getDefaultDriverClass())
                .status(DsStatus.ENABLED.getCode()).isDeleted(0).build();
        when(factory.create(any(DsConfig.class))).thenReturn(new com.alibaba.druid.pool.DruidDataSource());
        when(store.save(any(DsConfig.class))).thenReturn(saved);

        String dsId = registry.register(spec);
        assertThat(dsId).isEqualTo("ds-ok");
    }

    @Test
    @DisplayName("register spaceNames 从 spec 透传到持久化 DsConfig（库集合边界随注册落底）")
    void should_register_when_specCarriesSpaceNames() {
        DsConfigSpec spec = spec("ds-multi").spaceNames(List.of("CRM_A", "CRM_B")).build();
        when(factory.create(any(DsConfig.class))).thenReturn(new com.alibaba.druid.pool.DruidDataSource());
        when(store.save(any(DsConfig.class))).thenAnswer(inv -> inv.getArgument(0, DsConfig.class));

        registry.register(spec);

        ArgumentCaptor<DsConfig> captor = ArgumentCaptor.forClass(DsConfig.class);
        verify(store).save(captor.capture());
        assertThat(captor.getValue().getSpaceNames()).containsExactly("CRM_A", "CRM_B");
    }

    @Test
    @DisplayName("register 接受 DENY_ONLY/ALL/mode 缺省 白名单（默认放行语义）")
    void should_accept_when_whitelistModeDenyOnlyOrAllOrMissing() {
        DsConfig saved = DsConfig.builder().dsId("ds-ok").dsName("n").dbType(DbType.MYSQL)
                .driverClass(DbType.MYSQL.getDefaultDriverClass())
                .status(DsStatus.ENABLED.getCode()).isDeleted(0).build();
        when(factory.create(any(DsConfig.class))).thenReturn(new com.alibaba.druid.pool.DruidDataSource());
        when(store.save(any(DsConfig.class))).thenReturn(saved);

        // DENY_ONLY 黑名单
        DsConfigSpec deny = spec("ds-ok")
                .whitelist(WhitelistRule.builder().tables(List.of("SECRET_LOG")).mode("DENY_ONLY").build())
                .build();
        assertThat(registry.register(deny)).isEqualTo("ds-ok");
        // ALL 全部放行
        DsConfigSpec all = spec("ds-ok")
                .whitelist(WhitelistRule.builder().mode("ALL").build())
                .build();
        assertThat(registry.register(all)).isEqualTo("ds-ok");
        // mode 缺省（null）合法（按 WhitelistRule 缺省语义）
        DsConfigSpec missing = spec("ds-ok")
                .whitelist(WhitelistRule.builder().tables(List.of("CUSTOMER")).build())
                .build();
        assertThat(registry.register(missing)).isEqualTo("ds-ok");
    }

    @Test
    @DisplayName("register 拒绝非法的白名单 mode（仅支持 ALLOW_ONLY/DENY_ONLY/ALL）")
    void should_reject_when_whitelistModeInvalid() {
        DsConfigSpec spec = spec("ds1")
                .whitelist(WhitelistRule.builder().tables(List.of("T")).mode("OPEN").build())
                .build();
        assertThatThrownBy(() -> registry.register(spec))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> {
                    DbAccessException ex = (DbAccessException) e;
                    assertThat(ex.getCode()).isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode());
                    assertThat(ex.getMessage()).contains("ALLOW_ONLY/DENY_ONLY/ALL");
                });
    }

    @Test
    @DisplayName("register 拒绝白名单 tables/columns 注入字符或非法 token")
    void should_reject_when_whitelistTokenIllegal() {
        // tables 含分号注入字符
        DsConfigSpec injection = spec("ds1")
                .whitelist(WhitelistRule.builder().tables(List.of("T; DROP TABLE x"))
                        .mode("DENY_ONLY").build())
                .build();
        assertThatThrownBy(() -> registry.register(injection))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
        // columns 列名含引号/空白
        DsConfigSpec badColumn = spec("ds1")
                .whitelist(WhitelistRule.builder().tables(List.of("T"))
                        .columns(Map.of("T", List.of("ID \"1\"", "name name")))
                        .mode("ALLOW_ONLY").build())
                .build();
        assertThatThrownBy(() -> registry.register(badColumn))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
        // 空表名/超长 token
        DsConfigSpec blankToken = spec("ds1")
                .whitelist(WhitelistRule.builder().tables(List.of("   ")).mode("DENY_ONLY").build())
                .build();
        assertThatThrownBy(() -> registry.register(blankToken))
                .isInstanceOf(DbAccessException.class);
        String longToken = "T".repeat(129);
        DsConfigSpec tooLong = spec("ds1")
                .whitelist(WhitelistRule.builder().tables(List.of(longToken)).mode("DENY_ONLY").build())
                .build();
        assertThatThrownBy(() -> registry.register(tooLong))
                .isInstanceOf(DbAccessException.class);
    }

    @Test
    @DisplayName("register null spec 抛 PARAM_INVALID")
    void should_reject_when_specNull() {
        assertThatThrownBy(() -> registry.register(null))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
    }

    @Test
    @DisplayName("listConfigs 仅返回启用数据源的脱敏视图")
    void should_listEnabled_when_store() {
        DsConfig on = DsConfig.builder().dsId("on").dsName("n").dbType(DbType.MYSQL)
                .status(1).isDeleted(0).build();
        when(store.listEnabled()).thenReturn(List.of(on));
        List<DsConfigView> views = registry.listConfigs();
        assertThat(views).hasSize(1);
        assertThat(views.get(0).getDsId()).isEqualTo("on");
    }

    private DsConfigSpec.DsConfigSpecBuilder spec(String dsId) {
        return DsConfigSpec.builder()
                .dsId(dsId)
                .dsName("测试源")
                .dbType(DbType.MYSQL)
                .jdbcUrl("jdbc:mysql://localhost:3306/testdb")
                .readonlyUser("readonly_user")
                .readonlyPasswordRef("${DBACCESS_TEST_PWD}");
    }
}
