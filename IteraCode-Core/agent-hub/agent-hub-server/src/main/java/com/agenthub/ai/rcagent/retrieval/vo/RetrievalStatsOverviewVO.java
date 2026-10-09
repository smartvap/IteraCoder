package com.agenthub.ai.rcagent.retrieval.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 检索质量总览聚合行（overview：total_search/cold_start_rate/avg_latency_ms/avg_context_items/kb_b_degrade_rate）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalStatsOverviewVO {

    /** 检索次数 */
    private Long totalSearch = 0L;

    /** 冷启动次数 */
    private Long coldStartCount = 0L;

    /** 冷启动率（0~1，两位小数） */
    private BigDecimal coldStartRate = BigDecimal.ZERO;

    /** 平均耗时（毫秒） */
    private Long avgLatencyMs = 0L;

    /** 平均上下文条目 */
    private BigDecimal avgContextItems = BigDecimal.ZERO;

    /**
     * 知识库 B 降级率（0~1）。
     *
     * <p>字段名与 MyBatis XML 别名 {@code kb_b_degrade_rate} 驼峰映射一致（自动映射到
     * {@code kbBdegradeRate}）；因下划线命名策略无法把该缩写精确还原为契约键，显式指定
     * {@code @JsonProperty} 保证接口 3 输出 {@code kb_b_degrade_rate}（此前字段误写
     * {@code kbBDeGradeRate} 输出 {@code kb_b_de_grade_rate} 且无法被 MyBatis 自动映射）。</p>
     */
    @JsonProperty("kb_b_degrade_rate")
    private BigDecimal kbBdegradeRate = BigDecimal.ZERO;
}
