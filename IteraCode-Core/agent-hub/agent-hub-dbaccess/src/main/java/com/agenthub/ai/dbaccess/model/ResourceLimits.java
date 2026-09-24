package com.agenthub.ai.dbaccess.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资源限制覆盖上下文（data-model.md ResourceLimitKey，对应 t_ds_config.limits_json）。
 *
 * <p>各字段均可空：空表示未覆盖、回落到模块默认值（DbAccessProperties.default-limits，
 * 代码不硬编码 1000/10 等魔法值）。执行期行数与时间限制采用「请求值 ∩ 数据源配置值 ∩ 模块默认值」中最严格者。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceLimits {

    /** 最大返回行数（覆盖键 maxRows，模块默认经配置注入） */
    private Integer maxRows;

    /** 最大执行秒数（覆盖键 maxSeconds） */
    private Integer maxSeconds;

    /** 敏感字段策略（覆盖键 sensitivePolicy，模块默认 DENY） */
    private SensitivePolicy sensitivePolicy;

    /** 是否放行 SELECT *（覆盖键 allowSelectStar，模块默认 false） */
    private Boolean allowSelectStar;

    /**
     * 将本对象作为「数据源覆盖层」，叠加到 base 默认限制上，返回完整限制。
     */
    public ResourceLimits overlayOn(ResourceLimits base) {
        return ResourceLimits.builder()
                .maxRows(this.maxRows != null ? this.maxRows : base.maxRows)
                .maxSeconds(this.maxSeconds != null ? this.maxSeconds : base.maxSeconds)
                .sensitivePolicy(this.sensitivePolicy != null ? this.sensitivePolicy : base.sensitivePolicy)
                .allowSelectStar(this.allowSelectStar != null ? this.allowSelectStar : base.allowSelectStar)
                .build();
    }
}
