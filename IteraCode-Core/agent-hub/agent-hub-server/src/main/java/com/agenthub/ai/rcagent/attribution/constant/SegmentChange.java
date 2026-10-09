package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 子群变化类型（data-model §5 SegmentChange，rcagent_attribution_factor.segment_change）。
 */
public enum SegmentChange {

    /** 上升 */
    INCREASE,
    /** 下降 */
    DECREASE,
    /** 本期新增 */
    NEW,
    /** 基期存在本期消失 */
    DISAPPEARED,
    /** 平稳 */
    STABLE;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (SegmentChange t : values()) {
            if (t.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
