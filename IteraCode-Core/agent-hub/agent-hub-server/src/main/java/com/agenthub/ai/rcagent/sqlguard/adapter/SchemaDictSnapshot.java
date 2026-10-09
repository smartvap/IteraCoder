package com.agenthub.ai.rcagent.sqlguard.adapter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Schema 字典检索结果快照（SchemaDictionaryAdapter 内部出参，供 L2 SchemaChecker 消费）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaDictSnapshot {

    /** 数据源 */
    private String dsId;

    /** 字典可用标记（kb HTTP 可达并返回 200 且含检索能力，不代表表一定收录） */
    private Boolean available;

    /** 来源标识：KB_DICTIONARY / RUNTIME_METADATA / MIXED / UNAVAILABLE */
    private String source;

    /** 命中的表名集合（按用户输入表名精确匹配 kb 表候选） */
    @Builder.Default
    private List<String> hitTables = new ArrayList<>();

    /** 命中的限定表名集合（schema.table，kb 返回 schema_name 时使用；无 schema 退化为纯表名） */
    @Builder.Default
    private List<String> hitQualifiedTables = new ArrayList<>();

    /** 列字典：表名 -> 列信息（来自 kb columns 候选或运行时元数据） */
    @Builder.Default
    private List<SchemaColumnHit> columns = new ArrayList<>();

    /** 关系证据：来源 kb relation 候选 */
    @Builder.Default
    private List<RelationEvidence> relations = new ArrayList<>();
}
