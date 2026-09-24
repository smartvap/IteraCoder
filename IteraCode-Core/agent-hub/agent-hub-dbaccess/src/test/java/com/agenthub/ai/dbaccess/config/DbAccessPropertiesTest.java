package com.agenthub.ai.dbaccess.config;

import com.agenthub.ai.dbaccess.model.DsConfigSpec;
import com.agenthub.ai.dbaccess.model.ResourceLimits;
import com.agenthub.ai.dbaccess.model.SensitivePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DbAccessProperties 配置默认限制注入测试（避免硬编码魔法值）。
 */
class DbAccessPropertiesTest {

    @Test
    @DisplayName("default-limits 默认值：maxRows=1000/maxSeconds=10/DENY/allowSelectStar=false")
    void should_haveDefaults_when_notConfigured() {
        DbAccessProperties props = new DbAccessProperties();
        ResourceLimits limits = props.defaultLimitsModel();
        assertThat(limits.getMaxRows()).isEqualTo(1000);
        assertThat(limits.getMaxSeconds()).isEqualTo(10);
        assertThat(limits.getSensitivePolicy()).isEqualTo(SensitivePolicy.DENY);
        assertThat(limits.getAllowSelectStar()).isFalse();
    }

    @Test
    @DisplayName("配置覆盖后 defaultLimitsModel 返回覆盖值")
    void should_applyOverride_when_configured() {
        DbAccessProperties props = new DbAccessProperties();
        DbAccessProperties.DefaultLimits dl = props.getDefaultLimits();
        dl.setMaxRows(200);
        dl.setMaxSeconds(3);
        dl.setSensitivePolicy(SensitivePolicy.MASK);
        dl.setAllowSelectStar(true);
        ResourceLimits limits = props.defaultLimitsModel();
        assertThat(limits.getMaxRows()).isEqualTo(200);
        assertThat(limits.getMaxSeconds()).isEqualTo(3);
        assertThat(limits.getSensitivePolicy()).isEqualTo(SensitivePolicy.MASK);
        assertThat(limits.getAllowSelectStar()).isTrue();
    }

    @Test
    @DisplayName("DataSourceEntry 默认 status=1（启用）")
    void should_defaultStatus_when_entry() {
        DbAccessProperties.DataSourceEntry entry = new DbAccessProperties.DataSourceEntry();
        assertThat(entry.getStatus()).isEqualTo(1);
    }

    @Test
    @DisplayName("DsConfigSpec.toConfig 生成启用配置并透传 spaceNames")
    void should_toConfig_when_spec() {
        DsConfigSpec spec = DsConfigSpec.builder()
                .dsId("ds1").dsName("n").jdbcUrl("jdbc:mysql://localhost:3306/db")
                .readonlyUser("ro").readonlyPasswordRef("${DBACCESS_DS1_PWD}")
                .spaceNames(List.of("CRM_A", "CRM_B"))
                .build();
        com.agenthub.ai.dbaccess.model.DsConfig config = spec.toConfig();
        assertThat(config.enabled()).isTrue();
        assertThat(config.getVersion()).isZero();
        assertThat(config.getIsDeleted()).isZero();
        assertThat(config.getSpaceNames()).containsExactly("CRM_A", "CRM_B");
    }
}
