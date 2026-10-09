package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 因子类型（data-model §5 FactorType，rcagent_attribution_factor.factor_type）。
 */
public enum FactorType {

    /** 子群贡献因子（FR-003 主类型） */
    SEGMENT_CONTRIB,
    /** 新出现子群（基期为 0） */
    NEW_SEGMENT,
    /** 消失子群（本期为 0） */
    DISAPPEARED_SEGMENT,
    /** 结构变化（占比显著偏移） */
    STRUCTURAL_CHANGE,
    /** 口径变化（FR-005） */
    CALIBER_CHANGE,
    /** 数据质量问题（FR-005） */
    DATA_QUALITY;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (FactorType t : values()) {
            if (t.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
