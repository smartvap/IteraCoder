package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 规划 Plan 只读视图（data-model.md §6 SqlPlanView，L3/L5 对照入参）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlPlanView {

    /** 用户原始问题（日志截断） */
    private String question;

    /** 目标数据源 */
    private String dsId;

    /** 规划涉及表 */
    @Builder.Default
    private List<String> targetTables = new ArrayList<>();

    /** 指标/聚合项（如 投诉量/环比） */
    @Builder.Default
    private List<String> metrics = new ArrayList<>();

    /** 维度/分组 */
    @Builder.Default
    private List<String> dimensions = new ArrayList<>();

    /** 时间范围意图 {start,end} */
    private TimeRange timeRange;

    /** 过滤口径摘要 */
    @Builder.Default
    private List<String> filters = new ArrayList<>();

    /** 聚合口径摘要（可选） */
    private String aggKernel;
}
