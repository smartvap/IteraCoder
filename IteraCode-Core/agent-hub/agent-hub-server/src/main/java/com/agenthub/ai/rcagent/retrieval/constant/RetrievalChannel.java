package com.agenthub.ai.rcagent.retrieval.constant;

/**
 * 各路召回通道常量（channel_hit_json 键，数据字典 RetrievalChannel）。
 *
 * <p>value 即观测日志 channel_hit_json 中使用的键名，与 data-model.md 数据字典一致。</p>
 */
public enum RetrievalChannel {

    /** 表候选召回（A） */
    SCHEMA_TABLES("schema_tables"),
    /** 字段候选召回（A） */
    SCHEMA_COLUMNS("schema_columns"),
    /** SQL 模板召回（A） */
    TEMPLATES("templates"),
    /** 业务链路召回（A） */
    BIZ_LINKS("biz_links"),
    /** 表关系召回（A） */
    RELATIONS("relations"),
    /** 指标定义召回（A） */
    METRICS("metrics"),
    /** 业务概念召回（A） */
    CONCEPTS("concepts"),
    /** llm-wiki 文档语义召回（B） */
    WIKI_DOCS("wiki_docs"),
    /** 索引召回（A，随命中表返回 kb_index）：追加到末尾，避免影响既有 values() 顺序语义 */
    SCHEMA_INDEXES("schema_indexes");

    private final String value;

    RetrievalChannel(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /** 按值解析；不存在返回 null（由调用方忽略未知通道键） */
    public static RetrievalChannel from(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        for (RetrievalChannel c : values()) {
            if (c.value.equals(v)) {
                return c;
            }
        }
        return null;
    }
}
