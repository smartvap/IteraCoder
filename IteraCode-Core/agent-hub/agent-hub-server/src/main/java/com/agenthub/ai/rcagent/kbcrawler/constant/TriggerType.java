package com.agenthub.ai.rcagent.kbcrawler.constant;

/**
 * 采集触发来源（data-model 数据字典 TriggerType）。
 */
public enum TriggerType {

    /** 手动触发（API） */
    MANUAL("MANUAL"),
    /** 定时调度（@Scheduled） */
    SCHEDULE("SCHEDULE"),
    /** 发版触发（部署钩子/接口） */
    RELEASE("RELEASE");

    private final String value;

    TriggerType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (TriggerType t : values()) {
            if (t.value.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
