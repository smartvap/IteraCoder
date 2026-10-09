package com.agenthub.ai.rcagent.attribution.constant;

/**
 * 触发方式（data-model §5 TriggerType，rcagent_attribution_task.trigger_type）。
 */
public enum TriggerType {

    /** 指标结果触发异常识别（自动） */
    METRIC_ANOMALY,
    /** 用户追问"为什么 X 上升/下降" */
    USER_FOLLOW_UP;

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (TriggerType t : values()) {
            if (t.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}
