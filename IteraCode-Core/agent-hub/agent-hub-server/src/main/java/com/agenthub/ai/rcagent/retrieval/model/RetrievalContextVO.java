package com.agenthub.ai.rcagent.retrieval.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一检索上下文（RetrievalContextVO，api-contract data.context）。
 *
 * <p>schema/template/semantic/faq 分组视图 + items 全量命中（等价 data.items 展开）。
 * schema_items：表/字段/关系/业务链路；template_items：SQL 模板；
 * semantic_items：指标/概念；faq_items：知识库 B 文档。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalContextVO {

    /** schema 上下文（表/字段/关系/链路候选） */
    private List<RetrievalHit> schemaItems = new ArrayList<>();

    /** 模板上下文（SQL 模板候选） */
    private List<RetrievalHit> templateItems = new ArrayList<>();

    /** 口径上下文（指标/概念） */
    private List<RetrievalHit> semanticItems = new ArrayList<>();

    /** FAQ/规则上下文（B 库文档） */
    private List<RetrievalHit> faqItems = new ArrayList<>();

    /** 统一命中条目（已排序去重） */
    private List<RetrievalHit> items = new ArrayList<>();
}
