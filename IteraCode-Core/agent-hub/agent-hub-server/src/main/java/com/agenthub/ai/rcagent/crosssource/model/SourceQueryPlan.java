package com.agenthub.ai.rcagent.crosssource.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单源目标计划（内存模型，逻辑表 {@code t_source_query_plan}，**不落库**）。
 *
 * <p>由 {@link CrossSourceJoinPlan} 的单源部分派生，供逐源 SQL 生成节点组装提示词
 * （目标表 / 期望投影 / 过滤下推 / 聚合下推 / 期望形态）。字段见 {@code data-model.md §3.2}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceQueryPlan {

    /** 源序号（1/2，对外唯一标识） */
    private Integer sourceIndex;

    /** 源 dsId（内部） */
    private String dsId;

    /** 目标表（内部） */
    private String targetTable;

    /** 期望投影（语义化别名） */
    @Builder.Default
    private List<String> projections = new ArrayList<>();

    /** 过滤条件（join 前下推） */
    @Builder.Default
    private List<String> filters = new ArrayList<>();

    /** 聚合方式与目标列 */
    private String aggregation;

    /** 期望 SQL 形态（如 SELECT-AGG-WHERE） */
    private String expectedShape;
}
