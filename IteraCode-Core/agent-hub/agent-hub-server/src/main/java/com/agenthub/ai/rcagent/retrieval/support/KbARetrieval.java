package com.agenthub.ai.rcagent.retrieval.support;

import com.agenthub.ai.rcagent.retrieval.constant.RetrievalChannel;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库 A 五路召回门面（KbARetrieval）。
 *
 * <p>组合 {@link RetrievalAdapter} 完成 schema/template/biz-link/relation/semantic 五路召回，
 * 并把各通道命中聚合成统一结果（hits + channelHits）。语义路命中若已由
 * KnowledgeConceptResolver 产出则直接复用（避免二次检索），否则由 adapter 内部召回一次。</p>
 */
@Component
public class KbARetrieval {

    private final RetrievalAdapter adapter;

    public KbARetrieval(RetrievalAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * A 库五路召回。
     *
     * @param dsId           数据源 ID
     * @param keyword        主关键词
     * @param relatedTables  概念回填表名候选（可空，schema 精确表名过滤 + relation 表对配对）
     * @param relatedColumns 概念回填字段候选（可空，schema 列级补召回，FR-BE-001）
     * @param questionTokens 问题中提取的标识符 token（可空，供「命中表列展开」按需只展开问题点名的列）
     * @param includeDraft   是否含草稿
     * @param topK           每路条数
     * @param semanticHits   Resolver 已产出的语义命中（可为空）
     */
    public AOutcome searchA(String dsId, String keyword, List<String> relatedTables, List<String> relatedColumns,
                            List<String> questionTokens,
                            boolean includeDraft, int topK, List<RetrievalHit> semanticHits) {
        AOutcome outcome = new AOutcome();
        Map<RetrievalChannel, List<RetrievalHit>> routes = adapter.searchARoutes(
                dsId, keyword, relatedTables, relatedColumns, questionTokens, includeDraft, topK, semanticHits);
        Map<String, Integer> channelHits = new LinkedHashMap<>();
        for (RetrievalChannel channel : RetrievalChannel.values()) {
            channelHits.put(channel.getValue(), 0);
        }
        List<RetrievalHit> hits = new ArrayList<>();
        if (routes != null) {
            for (Map.Entry<RetrievalChannel, List<RetrievalHit>> entry : routes.entrySet()) {
                List<RetrievalHit> list = entry.getValue() == null ? new ArrayList<>() : entry.getValue();
                channelHits.put(entry.getKey().getValue(), list.size());
                hits.addAll(list);
            }
        }
        outcome.setChannelHits(channelHits);
        outcome.setHits(hits);
        return outcome;
    }

    /** A 库召回结果（内部使用） */
    @Data
    public static class AOutcome {

        /** 各路命中统计（channel 键） */
        private Map<String, Integer> channelHits = new LinkedHashMap<>();

        /** 五路聚合统一命中 */
        private List<RetrievalHit> hits = new ArrayList<>();
    }
}
