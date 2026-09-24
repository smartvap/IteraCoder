package com.agenthub.ai.dbaccess.store;

import com.agenthub.ai.dbaccess.config.DbAccessProperties;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfig;
import com.agenthub.ai.dbaccess.model.DsStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ConfigDrivenDsConfigStore 配置驱动存储测试（yml 加载 + 幂等覆盖 + 逻辑删除）。
 */
class ConfigDrivenDsConfigStoreTest {

    private DbAccessProperties props() {
        DbAccessProperties p = new DbAccessProperties();
        DbAccessProperties.DataSourceEntry e1 = new DbAccessProperties.DataSourceEntry();
        e1.setDsId("ds-a");
        e1.setDsName("A");
        e1.setDbType("MYSQL");
        e1.setJdbcUrl("jdbc:mysql://localhost:3306/a");
        e1.setReadonlyUser("ro");
        e1.setReadonlyPasswordRef("${DBACCESS_DS_A_PWD}");
        DbAccessProperties.DataSourceEntry e2 = new DbAccessProperties.DataSourceEntry();
        e2.setDsId("ds-off");
        e2.setDsName("Off");
        e2.setDbType("oracle");
        e2.setStatus(DsStatus.DISABLED.getCode());
        p.setDatasources(List.of(e1, e2));
        return p;
    }

    @Test
    @DisplayName("yml 配置驱动加载：findByDsId/listEnabled/listAll")
    void should_loadFromProperties_when_init() {
        ConfigDrivenDsConfigStore store = new ConfigDrivenDsConfigStore(props());

        DsConfig a = store.findByDsId("ds-a");
        assertThat(a).isNotNull();
        assertThat(a.getDbType()).isEqualTo(DbType.MYSQL);
        assertThat(a.getDriverClass()).isEqualTo("com.mysql.cj.jdbc.Driver"); // 推断驱动
        assertThat(a.getReadonlyPasswordRef()).isEqualTo("${DBACCESS_DS_A_PWD}");
        assertThat(store.findByDsId("nope")).isNull();
        assertThat(store.listAll()).hasSize(2);
        assertThat(store.listEnabled()).extracting(DsConfig::getDsId).containsExactly("ds-a");
    }

    @Test
    @DisplayName("yml 驱动路由：OCEANBASE_MYSQL + jdbc:mysql:（存量）→ 解析为 mysql-connector 驱动")
    void should_routeMysqlDriver_when_oceanbaseWithMysqlUrl() {
        DbAccessProperties p = new DbAccessProperties();
        DbAccessProperties.DataSourceEntry e = new DbAccessProperties.DataSourceEntry();
        e.setDsId("ds-ob-mysql");
        e.setDbType("OCEANBASE_MYSQL");
        e.setJdbcUrl("jdbc:mysql://host:3306/db");
        p.setDatasources(List.of(e));

        ConfigDrivenDsConfigStore store = new ConfigDrivenDsConfigStore(p);

        assertThat(store.findByDsId("ds-ob-mysql").getDriverClass()).isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    @DisplayName("yml 驱动路由：OCEANBASE_ORACLE + jdbc:oceanbase: → com.oceanbase.jdbc.Driver")
    void should_routeOceanBaseDriver_when_oceanbaseUrl() {
        DbAccessProperties p = new DbAccessProperties();
        DbAccessProperties.DataSourceEntry e = new DbAccessProperties.DataSourceEntry();
        e.setDsId("ds-ob-oracle");
        e.setDbType("OCEANBASE_ORACLE");
        e.setJdbcUrl("jdbc:oceanbase://host:2881/db");
        p.setDatasources(List.of(e));

        ConfigDrivenDsConfigStore store = new ConfigDrivenDsConfigStore(p);

        assertThat(store.findByDsId("ds-ob-oracle").getDriverClass()).isEqualTo("com.oceanbase.jdbc.Driver");
    }

    @Test
    @DisplayName("yml 驱动路由：显式 driverClass 优先于 URL 前缀与库类型默认")
    void should_preferExplicitDriver_when_configured() {
        DbAccessProperties p = new DbAccessProperties();
        DbAccessProperties.DataSourceEntry e = new DbAccessProperties.DataSourceEntry();
        e.setDsId("ds-explicit");
        e.setDbType("MYSQL");
        e.setJdbcUrl("jdbc:mysql://host:3306/db");
        e.setDriverClass("com.custom.Driver");
        p.setDatasources(List.of(e));

        ConfigDrivenDsConfigStore store = new ConfigDrivenDsConfigStore(p);

        assertThat(store.findByDsId("ds-explicit").getDriverClass()).isEqualTo("com.custom.Driver");
    }

    @Test
    @DisplayName("findByDsId 返回副本，外部修改不影响内部")
    void should_returnCopy_when_find() {        ConfigDrivenDsConfigStore store = new ConfigDrivenDsConfigStore(props());
        DsConfig a = store.findByDsId("ds-a");
        a.setDsName("changed");
        assertThat(store.findByDsId("ds-a").getDsName()).isEqualTo("A");
    }

    @Test
    @DisplayName("yml 配置驱动加载 space-names：可访问库集合透传（findByDsId/listAll 副本均保留）")
    void should_loadSpaceNames_when_entryConfigured() {
        DbAccessProperties p = new DbAccessProperties();
        DbAccessProperties.DataSourceEntry e1 = new DbAccessProperties.DataSourceEntry();
        e1.setDsId("ds-multi");
        e1.setDsName("Multi");
        e1.setDbType("MYSQL");
        e1.setJdbcUrl("jdbc:mysql://localhost:3306/a");
        e1.setReadonlyUser("ro");
        e1.setReadonlyPasswordRef("${DBACCESS_DS_MULTI_PWD}");
        e1.setSpaceNames(List.of("CRM_A", "CRM_B"));
        p.setDatasources(List.of(e1));

        ConfigDrivenDsConfigStore store = new ConfigDrivenDsConfigStore(p);

        assertThat(store.findByDsId("ds-multi").getSpaceNames()).containsExactly("CRM_A", "CRM_B");
        assertThat(store.listAll()).extracting(DsConfig::getSpaceNames)
                .containsExactly(List.of("CRM_A", "CRM_B"));
    }

    @Test
    @DisplayName("save 幂等覆盖：同 ds_id 覆盖 + version 递增")
    void should_saveIdempotent_when_override() {
        ConfigDrivenDsConfigStore store = new ConfigDrivenDsConfigStore(props());
        DsConfig base = store.findByDsId("ds-a");
        assertThat(base.getVersion()).isZero();

        DsConfig update = DsConfig.builder()
                .dsId("ds-a")
                .dsName("A2")
                .dbType(DbType.MYSQL)
                .status(DsStatus.ENABLED.getCode())
                .isDeleted(0)
                .build();
        DsConfig saved = store.save(update);
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(store.findByDsId("ds-a").getVersion()).isEqualTo(1);
        assertThat(store.findByDsId("ds-a").getDsName()).isEqualTo("A2");
    }

    @Test
    @DisplayName("save 新 ds_id 创建（初始 version=0）")
    void should_saveNew_when_dsIdAbsent() {
        ConfigDrivenDsConfigStore store = new ConfigDrivenDsConfigStore(props());
        DsConfig fresh = DsConfig.builder()
                .dsId("ds-new")
                .dsName("New")
                .dbType(DbType.MYSQL)
                .status(DsStatus.ENABLED.getCode())
                .isDeleted(0)
                .build();
        DsConfig saved = store.save(fresh);
        assertThat(saved.getVersion()).isZero();
        assertThat(store.findByDsId("ds-new")).isNotNull();
    }

    @Test
    @DisplayName("save 空 ds_id 抛 PARAM_INVALID")
    void should_throw_when_dsIdBlank() {
        ConfigDrivenDsConfigStore store = new ConfigDrivenDsConfigStore(props());
        assertThatThrownBy(() -> store.save(DsConfig.builder().dsId("  ").build()))
                .isInstanceOf(DbAccessException.class)
                .satisfies(e -> assertThat(((DbAccessException) e).getCode())
                        .isEqualTo(DbAccessErrorCode.PARAM_INVALID.getCode()));
        assertThatThrownBy(() -> store.save(null)).isInstanceOf(DbAccessException.class);
    }
}
