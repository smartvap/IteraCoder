package com.agenthub.ai.rcagent.retrieval.support;

import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 概念/指标回填结果（FR-001）：命中 kb_business_concept/kb_metric 后回填 related_* 作为追加检索条件。
 */
@Data
public class ConceptResolveResult {

    /** 关联表名候选（related_tables 解析，用于 schema 精确表名过滤） */
    private List<String> relatedTables = new ArrayList<>();

    /** 关联字段候选（related_columns 解析，用于 schema 关键词追加） */
    private List<String> relatedColumns = new ArrayList<>();

    /** 关联 SQL 模板 id 字符串候选（related_sql_ids 解析） */
    private List<String> relatedSqlIds = new ArrayList<>();

    /** 语义路命中（A_METRIC/A_CONCEPT）统一条目，避免 A 五路重复召回 */
    private List<RetrievalHit> semanticHits = new ArrayList<>();

    /** A 库语义命中携带的 llm-wiki 空间名（B 路空间解析优先级 3） */
    private String spaceName;
}
