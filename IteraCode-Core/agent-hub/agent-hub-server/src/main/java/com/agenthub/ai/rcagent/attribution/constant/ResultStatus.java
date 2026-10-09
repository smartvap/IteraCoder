package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 归因结果类型（data-model §5 ResultStatus，rcagent_attribution_task.result_status）。
 */
public enum ResultStatus {

    /** 指标平稳（无异常，返回平稳说明） */
    STABLE,
    /** 业务真异常 → 归因结论 */
    CONCLUSION,
    /** 口径/数据质量问题报告（FR-005） */
    DATA_ISSUE,
    /** 无口径定义/降级（仅数据展示或人工确认，BR-001） */
    DEGRADED,
    /** 结论被打回，修正后重跑/降级 */
    REJECTED_RETRY;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (ResultStatus s : values()) {
            if (s.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
