package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 对比方式（data-model §5 CompareType，rcagent_attribution_task.compare_type）。
 */
public enum CompareType {

    /** 环比（本期 vs 上一期） */
    MOM,
    /** 同比（本期 vs 去年同期） */
    YOY,
    /** 自定义基期 */
    CUSTOM;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (CompareType t : values()) {
            if (t.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
