package com.agenthub.ai.rcagent.retrieval.constant;

/**
 * 统一命中条目来源标注（source 字段，数据字典 RetrievalSourceType）。
 *
 * <p>A_* 为知识库 A（module-004 kb_*）召回产物；B_WIKI_DOC 为知识库 B（llm-wiki）召回产物。
 * 值保持与 data-model.md 数据字典逐字一致。</p>
 */
public enum RetrievalSourceType {

    /** 知识库 A 表候选 */
    A_SCHEMA_TABLE("A_SCHEMA_TABLE"),
    /** 知识库 A 字段候选 */
    A_SCHEMA_COLUMN("A_SCHEMA_COLUMN"),
    /** 知识库 A SQL 模板 */
    A_TEMPLATE("A_TEMPLATE"),
    /** 知识库 A 业务链路 */
    A_BIZ_LINK("A_BIZ_LINK"),
    /** 知识库 A 表关系 */
    A_RELATION("A_RELATION"),
    /** 知识库 A 指标定义 */
    A_METRIC("A_METRIC"),
    /** 知识库 A 业务概念 */
    A_CONCEPT("A_CONCEPT"),
    /** 知识库 B llm-wiki 文档 */
    B_WIKI_DOC("B_WIKI_DOC"),
    /** 知识库 A 索引命中（随命中表返回 kb_index）：追加到末尾，不改既有取值 */
    A_SCHEMA_INDEX("A_SCHEMA_INDEX");

    private final String value;

    RetrievalSourceType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public boolean isKbA() {
        return this != B_WIKI_DOC;
    }
}
