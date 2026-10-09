package com.agenthub.ai.rcagent.retrieval.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 融合检索主入口入参（接口1 POST /api/v1/retrieval/search）。
 *
 * <p>字段与 api-contract 接口 1 请求参数对齐；ds_id 必填（BR-001），
 * question 与 query_elements 至少一项非空。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalRequest {

    /** 数据源实例 ID（BR-001 显式路由） */
    private String dsId;

    /** 用户问题原文 */
    private String question;

    /** 意图要素 */
    private QueryElements queryElements;

    /** 过滤项 */
    private Filters filters;

    /** 合并去重后最终保留条目数，默认 20 */
    private Integer topK;

    /** 每路召回通道上限，默认 10 */
    private Integer perChannelTopK;

    /** 知识库 A 召回开关，默认 true */
    private Boolean enableKbA;

    /** 知识库 B 召回开关，默认 true */
    private Boolean enableKbB;

    /** Rerank 精排开关（P2），默认 false */
    private Boolean rerankEnabled;

    /** 允许冷生成降级（FR-006），默认 false */
    private Boolean coldStartAllowed;

    /** 关联工作流会话 thread_id（质量日志追溯） */
    private String threadId;

    // ============ 便捷读取（避免调用处重复判空） ============

    public boolean enableKbAValue() {
        return enableKbA == null || enableKbA;
    }

    public boolean enableKbBValue() {
        return enableKbB == null || enableKbB;
    }

    public boolean rerankEnabledValue() {
        return Boolean.TRUE.equals(rerankEnabled);
    }

    public boolean coldStartAllowedValue() {
        return Boolean.TRUE.equals(coldStartAllowed);
    }

    public int topKValue() {
        return topK == null || topK < 1 ? 20 : Math.min(topK, 100);
    }

    public int perChannelTopKValue() {
        return perChannelTopK == null || perChannelTopK < 1 ? 10 : Math.min(perChannelTopK, 50);
    }

    /** filters 缺省返回空对象（不产生 NPE），校验空在 Controller/Service 边界 */
    public Filters filtersValue() {
        return filters == null ? new Filters() : filters;
    }

    public boolean includeDraftValue() {
        Filters f = filtersValue();
        return Boolean.TRUE.equals(f.getIncludeDraft());
    }

    public BigDecimal confidenceMinValue() {
        Filters f = filtersValue();
        return f.getConfidenceMin();
    }

    public String spaceNameValue() {
        Filters f = filtersValue();
        return f.getSpaceName();
    }

    /** 汇总 query_elements 中全部自由词/关键词/实体，作为全文检索关键词来源 */
    public List<String> keywordTerms() {
        QueryElements qe = queryElements == null ? new QueryElements() : queryElements;
        if (qe.getKeywords() != null && !qe.getKeywords().isEmpty()) {
            return qe.getKeywords();
        }
        return java.util.List.of();
    }

    /** 是否存在 query_elements 要素（含 timeRange） */
    public boolean hasElements() {
        QueryElements qe = queryElements;
        if (qe == null) {
            return false;
        }
        return qe.getTimeRange() != null
                || notEmpty(qe.getDimensions())
                || notEmpty(qe.getMetrics())
                || notEmpty(qe.getEntities())
                || notEmpty(qe.getConcepts())
                || notEmpty(qe.getKeywords());
    }

    private static boolean notEmpty(List<String> list) {
        return list != null && !list.isEmpty();
    }

    /** 过滤项（filters 子结构） */
    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Filters {

        /** 状态 draft/active/deprecated，默认 active */
        private String status;

        /** 最小置信度（BigDecimal 0~1） */
        private BigDecimal confidenceMin;

        /** 是否含草稿（BR-002） */
        private Boolean includeDraft;

        /** 显式知识库 B 空间名 */
        private String spaceName;
    }
}
