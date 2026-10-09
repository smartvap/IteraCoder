package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 因子状态（data-model §5 FactorStatus，rcagent_attribution_factor.status）。
 */
public enum FactorStatus {

    /** 候选因子 */
    CANDIDATE,
    /** 主因 */
    MAIN_FACTOR,
    /** 次要因素 */
    SECONDARY,
    /** 打回剔除 */
    REJECTED;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (FactorStatus t : values()) {
            if (t.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
