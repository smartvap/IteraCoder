package com.agenthub.ai.dbaccess.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResourceLimits 覆盖层合并（数据源配置覆盖叠加到模块默认限制）测试。
 */
class ResourceLimitsTest {

    @Test
    @DisplayName("覆盖字段优先，未覆盖字段回落 base 默认值")
    void should_overlay_onBase_when_mixingFields() {
        ResourceLimits base = ResourceLimits.builder()
                .maxRows(1000)
                .maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.DENY)
                .allowSelectStar(false)
                .build();
        ResourceLimits dsOverride = ResourceLimits.builder()
                .maxRows(500)
                .sensitivePolicy(SensitivePolicy.MASK)
                .build();

        ResourceLimits merged = dsOverride.overlayOn(base);

        assertThat(merged.getMaxRows()).isEqualTo(500);
        assertThat(merged.getMaxSeconds()).isEqualTo(10);
        assertThat(merged.getSensitivePolicy()).isEqualTo(SensitivePolicy.MASK);
        assertThat(merged.getAllowSelectStar()).isFalse();
    }

    @Test
    @DisplayName("空覆盖层等价于模块默认限制")
    void should_returnBase_when_overrideAllNull() {
        ResourceLimits base = ResourceLimits.builder()
                .maxRows(1000)
                .maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.DENY)
                .allowSelectStar(false)
                .build();
        ResourceLimits empty = ResourceLimits.builder().build();

        ResourceLimits merged = empty.overlayOn(base);

        assertThat(merged).usingRecursiveComparison().isEqualTo(base);
    }

    @Test
    @DisplayName("数据源配置层可单独放宽 selectStar（由模块策略统一收敛）")
    void should_allowSelectStar_when_dsOverrideTrue() {
        ResourceLimits base = ResourceLimits.builder()
                .maxRows(1000)
                .maxSeconds(10)
                .sensitivePolicy(SensitivePolicy.DENY)
                .allowSelectStar(false)
                .build();
        ResourceLimits dsOverride = ResourceLimits.builder().allowSelectStar(true).build();

        assertThat(dsOverride.overlayOn(base).getAllowSelectStar()).isTrue();
    }
}
