package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * 接口20 Schema 检索召回入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbSchemaRetrievalDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 关键词（表名/字段名/注释模糊匹配） */
    private String keyword;

    /** 精确表名候选过滤 */
    private List<String> tableNames;

    /** 精确「库.表」限定名候选（形如 DB_A.T1；与 tableNames 二选一或并存，限定名优先） */
    private List<String> qualifiedTableNames;

    /** 语义标签过滤 */
    private List<String> semanticTags;

    /** topK，默认 20 */
    private Integer topK;

    /** 是否含草稿（仅审核场景），默认 false */
    private Boolean includeDraft;

    /** 是否同时召回索引候选（对外 include_indexes），默认 false：false 时不查询 kb_index，响应缺省 indexes */
    private Boolean includeIndexes;

    public int topK() {
        return topK == null || topK < 1 ? 20 : Math.min(topK, 100);
    }

    public boolean includeDraft() {
        return Boolean.TRUE.equals(includeDraft);
    }

    /** null 安全助手：未设置视为 false（保持既有响应逐字段等价） */
    public boolean includeIndexes() {
        return Boolean.TRUE.equals(includeIndexes);
    }
}
