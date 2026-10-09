package com.agenthub.ai.rcagent.retrieval.constant;

/**
 * 去重冲突标记（数据字典 DedupConflictFlag）。
 */
public enum ConflictFlag {

    /** 无冲突 */
    NONE("NONE"),
    /** 同一对象存在多条高置信冲突知识（保留全部 + 标记，由 Rerank/上层裁决） */
    CONFLICT("CONFLICT");

    private final String value;

    ConflictFlag(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
