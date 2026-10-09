package com.agenthub.ai.rcagent.kbcrawler.constant;

/**
 * 采集任务类型（data-model 数据字典 TaskType）。
 */
public enum TaskType {

    /** Schema 全量采集（表+列+统计+快照刷新） */
    SCHEMA_FULL("SCHEMA_FULL"),
    /** Schema 增量采集（按 last_ddl_time/columns_hash） */
    SCHEMA_INCR("SCHEMA_INCR"),
    /** Mapper 静态解析（XML+DAO 链路+列注释补充） */
    MAPPER_PARSE("MAPPER_PARSE"),
    /** 运行时采集（拦截器事件消费→归一化聚类→写模板/关系） */
    RUNTIME_COLLECT("RUNTIME_COLLECT"),
    /** 变更检测（diff 快照 → 处置动作 + 影响分析） */
    CHANGE_DETECT("CHANGE_DETECT");

    private final String value;

    TaskType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (TaskType t : values()) {
            if (t.value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    /** 按值解析；不存在返回 null */
    public static TaskType from(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        for (TaskType t : values()) {
            if (t.value.equals(v)) {
                return t;
            }
        }
        return null;
    }
}
