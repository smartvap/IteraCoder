package com.agenthub.ai.rcagent.retrieval.rerank;

import com.agenthub.ai.rcagent.retrieval.dto.RetrievalRequest;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;

import java.util.List;

/**
 * Rerank SPI（FR-BE-008 / FR-004 P2）。
 *
 * <p>P1 默认规则降级 {@link RuleFallbackReranker}（保持 FusionMerger 排序）；
 * P2 由 {@link BgeReranker}（@ConditionalOnProperty 外部 HTTP）实现，命中顺序发生变化。</p>
 */
public interface RetrievalReranker {

    /**
     * 精排统一命中。
     *
     * @param request 原始检索请求
     * @param hits    已合并去重排序的命中（FusionMerger 输出）
     * @return 精排后命中（默认不得破坏证据/冲突标记）
     */
    List<RetrievalHit> rerank(RetrievalRequest request, List<RetrievalHit> hits);
}
