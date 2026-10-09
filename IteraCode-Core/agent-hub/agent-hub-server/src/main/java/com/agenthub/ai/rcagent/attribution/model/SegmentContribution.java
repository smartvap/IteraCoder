package com.agenthub.ai.rcagent.attribution.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 贡献拆解计算产物（内存中间模型，落库 rcagent_attribution_factor 前载体）。
 */
@Data
public class SegmentContribution {

    /** 维度名 */
    private String dimension;

    /** 维度展示名 */
    private String dimensionLabel;

    /** 子群键 */
    private String segmentKey;

    /** 子群展示值 */
    private String segmentValue;

    /** 本期值 */
    private BigDecimal curValue = BigDecimal.ZERO;

    /** 基期值 */
    private BigDecimal baseValue = BigDecimal.ZERO;

    /** 段变化量 */
    private BigDecimal deltaValue = BigDecimal.ZERO;

    /** 段变化率 %（base=0 且新段置 null） */
    private BigDecimal deltaRatio;

    /** 对总变化的贡献量（同向 delta 语义） */
    private BigDecimal contribution = BigDecimal.ZERO;

    /** 贡献占比 % */
    private BigDecimal contributionRatio;

    /** 本期占比 % */
    private BigDecimal curShare;

    /** 基期占比 % */
    private BigDecimal baseShare;

    /** 变化类型 */
    private String segmentChange;

    /** 因子类型 */
    private String factorType;

    /** 贡献度排序位 */
    private Integer rank;

    /** 是否主因 */
    private boolean main;

    /** 支持证据查询（group cur/base 两条 query_id） */
    private List<String> evidenceQueryIds = new ArrayList<>();

    /** 聚合列名（区分 count(*) 与 sum(col)） */
    private String aggregateExpression;
}
