package com.agenthub.ai.rcagent.attribution.model;

import lombok.Data;

import java.util.List;

/**
 * 单步下钻计划（DrillPlanner 产物）：一个候选维度对应一个下钻步骤。
 */
@Data
public class DrillPlanStep {

    /** 下钻维度语义码（BRANCH/TIME_SLOT/BIZ_TYPE/CHANNEL/REGION/自定义字段名） */
    private String dimension;

    /** 维度展示名 */
    private String dimensionLabel;

    /** 实际参与分组的物理列（由口径 related_columns 解析，无匹配为 null → 跳过该步并提示） */
    private String column;

    /** 是否在本层聚焦某子群继续下钻（focus segment_key，可为 null） */
    private String focusSegmentKey;

    /** 聚焦子群展示值（可为 null） */
    private String focusSegmentValue;

    /** 推测来源（USER_DIMENSION/CALIBER_COLUMN/SCHEMA_HINT） */
    private String source;
}
