package com.agenthub.ai.dbaccess.security;

import com.agenthub.ai.dbaccess.config.DbAccessProperties;
import com.agenthub.ai.dbaccess.model.SensitiveFieldRule;
import com.agenthub.ai.dbaccess.model.SensitiveLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SensitiveFieldRegistry 敏感字段清单登记/合并/脱敏钩子测试。
 */
class SensitiveFieldRegistryTest {

    private DbAccessProperties properties;
    private SensitiveFieldRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new DbAccessProperties();
        registry = new SensitiveFieldRegistry(properties);
    }

    @Test
    @DisplayName("运行期登记全局规则与按 ds 规则，loadByDs 合并返回")
    void should_loadMerged_when_registered() {
        registry.registerSensitive("T", "PHONE", SensitiveLevel.HIGH); // 全局
        registry.registerSensitive("ds-a", "CUSTOMER", "IDCARD", SensitiveLevel.HIGH);

        List<SensitiveFieldRule> forA = registry.loadByDs("ds-a");
        assertThat(forA).anyMatch(r -> r.getTable().equals("T") && r.getColumn().equals("PHONE"));
        assertThat(forA).anyMatch(r -> r.getColumn().equals("IDCARD") && r.getDsId().equals("ds-a"));
        // 其他 ds 只拿到全局规则
        List<SensitiveFieldRule> forB = registry.loadByDs("ds-b");
        assertThat(forB).hasSize(1);
        assertThat(forB.get(0).getColumn()).isEqualTo("PHONE");
    }

    @Test
    @DisplayName("重复登记同 表/列 幂等覆盖（大小写不敏感）")
    void should_override_when_reRegisterSameColumn() {
        registry.registerSensitive("T", "PHONE", SensitiveLevel.HIGH);
        registry.registerSensitive("t", "phone", SensitiveLevel.MEDIUM);

        List<SensitiveFieldRule> rules = registry.loadByDs("any");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getLevel()).isEqualTo(SensitiveLevel.MEDIUM);
    }

    @Test
    @DisplayName("level 为空时默认 HIGH")
    void should_defaultHigh_when_levelNull() {
        registry.registerSensitive("T", "PWD", null);
        List<SensitiveFieldRule> rules = registry.loadByDs("any");
        assertThat(rules.get(0).getLevel()).isEqualTo(SensitiveLevel.HIGH);
    }

    @Test
    @DisplayName("配置级敏感规则按 dsId 过滤（dsId 空=全局生效）")
    void should_includeConfiguredRules_when_load() {
        SensitiveFieldRule global = SensitiveFieldRule.builder()
                .table("G1").column("C1").level(SensitiveLevel.LOW).build();
        SensitiveFieldRule dsA = SensitiveFieldRule.builder()
                .dsId("ds-a").table("A1").column("C1").level(SensitiveLevel.HIGH).build();
        properties.setSensitiveRules(List.of(global, dsA));

        List<SensitiveFieldRule> forA = registry.loadByDs("ds-a");
        assertThat(forA).hasSize(2);
        List<SensitiveFieldRule> forB = registry.loadByDs("ds-b");
        assertThat(forB).hasSize(1);
        assertThat(forB.get(0).getTable()).isEqualTo("G1");
    }

    @Test
    @DisplayName("yml/配置级规则漏配 level 时 loadByDs 归一为 HIGH（fail-closed，不污染配置源）")
    void should_normalizeConfiguredLevel_when_null() {
        // 模拟 yml 漏写 level（SensitiveFieldRule 反序列化对象 level 可能为 null）
        SensitiveFieldRule missing = SensitiveFieldRule.builder()
                .dsId("ds-a").table("CUSTOMER").column("PHONE").level(null).build();
        properties.setSensitiveRules(List.of(missing));

        List<SensitiveFieldRule> rules = registry.loadByDs("ds-a");
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getLevel()).isEqualTo(SensitiveLevel.HIGH);
        // 原配置对象不被就地修改（loadByDs 返回归一化副本）
        assertThat(missing.getLevel()).isNull();
    }

    @Test
    @DisplayName("mask 钩子委托 SensitiveMasker")
    void should_mask_when_delegate() {
        assertThat(registry.mask("13812348000", SensitiveLevel.HIGH)).isEqualTo("138****8000");
    }
}
