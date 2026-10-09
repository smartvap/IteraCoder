package com.agenthub.ai.rcagent.kbcrawler.constant;

/**
 * 采集任务状态（data-model 数据字典 TaskStatus）。
 */
public enum TaskStatus {

    /** 已登记待调度 */
    SCHEDULED("scheduled"),
    /** 运行中 */
    RUNNING("running"),
    /** 成功 */
    SUCCESS("success"),
    /** 失败（可重跑） */
    FAILED("failed");

    private final String value;

    TaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (TaskStatus s : values()) {
            if (s.value.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
