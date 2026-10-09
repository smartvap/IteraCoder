package com.agenthub.ai.rcagent.kbcrawler.constant;

/**
 * 采集游标键（data-model 数据字典 CursorKey）。
 */
public enum CursorKey {

    /** 最近 last_ddl_time（增量轮询游标） */
    SCHEMA_LAST_DDL("SCHEMA_LAST_DDL"),
    /** 最近一次全量采集集合哈希（全量判断用）；按 space 维度存储（kb_crawl_cursor.space_name 列），多库互不覆盖 */
    SCHEMA_FULL_HASH("SCHEMA_FULL_HASH"),
    /** Mapper 代码树指纹（代码变更检测用） */
    MAPPER_SRC_FINGERPRINT("MAPPER_SRC_FINGERPRINT"),
    /** 运行时已消费批次 id（上报幂等） */
    RUNTIME_BATCH_ID("RUNTIME_BATCH_ID");

    private final String value;

    CursorKey(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
