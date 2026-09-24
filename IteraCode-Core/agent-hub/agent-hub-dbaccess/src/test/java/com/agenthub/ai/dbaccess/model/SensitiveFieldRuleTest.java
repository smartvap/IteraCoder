package com.agenthub.ai.dbaccess.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SensitiveFieldRule 命中规则测试。
 */
class SensitiveFieldRuleTest {

    @Test
    @DisplayName("dsId 为空=全局规则命中任意数据源")
    void should_matchAnyDs_when_globalRule() {
        SensitiveFieldRule rule = SensitiveFieldRule.builder()
                .table("CUSTOMER").column("PHONE").level(SensitiveLevel.HIGH).build();
        assertThat(rule.matches("ds-a", "customer", "phone")).isTrue();
        assertThat(rule.matches("ds-b", "CUSTOMER", "PHONE")).isTrue();
        // 列不匹配不命中
        assertThat(rule.matches("ds-a", "CUSTOMER", "NAME")).isFalse();
    }

    @Test
    @DisplayName("dsId 指定时仅命中该数据源")
    void should_matchOnlyDs_when_dsRule() {
        SensitiveFieldRule rule = SensitiveFieldRule.builder()
                .dsId("ds-a").table("T").column("PHONE").level(SensitiveLevel.HIGH).build();
        assertThat(rule.matches("ds-a", "T", "PHONE")).isTrue();
        assertThat(rule.matches("ds-b", "T", "PHONE")).isFalse();
    }

    @Test
    @DisplayName("JSON 双向解析")
    void should_roundTrip_when_json() {
        String json = "{\"dsId\":\"ds-a\",\"table\":\"T\",\"column\":\"PHONE\",\"level\":\"HIGH\"}";
        SensitiveFieldRule rule = SensitiveFieldRule.fromJson(json);
        assertThat(rule).isNotNull();
        assertThat(rule.getLevel()).isEqualTo(SensitiveLevel.HIGH);
        assertThat(rule.toJson()).contains("ds-a");
        assertThat(SensitiveFieldRule.fromJson(null)).isNull();
    }

    @Test
    @DisplayName("level 缺省/显式 null 一律默认 HIGH（builder 与 JSON 反序列化一致，fail-closed）")
    void should_defaultHigh_when_levelMissing() {
        SensitiveFieldRule built = SensitiveFieldRule.builder()
                .table("T").column("PHONE").build();
        assertThat(built.getLevel()).isEqualTo(SensitiveLevel.HIGH);

        SensitiveFieldRule fromJson = SensitiveFieldRule.fromJson("{\"table\":\"T\",\"column\":\"PHONE\"}");
        assertThat(fromJson).isNotNull();
        assertThat(fromJson.getLevel()).isEqualTo(SensitiveLevel.HIGH);

        SensitiveFieldRule explicitNull = SensitiveFieldRule.builder()
                .table("T").column("PHONE").level(null).build();
        assertThat(explicitNull.effectiveLevel()).isEqualTo(SensitiveLevel.HIGH);

        // 显式 LOW 仍生效（可配置降级），effectiveLevel 不覆盖显式等级
        SensitiveFieldRule low = SensitiveFieldRule.builder()
                .table("T").column("PHONE").level(SensitiveLevel.LOW).build();
        assertThat(low.getLevel()).isEqualTo(SensitiveLevel.LOW);
        assertThat(low.effectiveLevel()).isEqualTo(SensitiveLevel.LOW);
    }
}
