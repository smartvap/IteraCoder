package com.agenthub.ai.rcagent.retrieval.rerank;

import com.agenthub.ai.rcagent.retrieval.dto.RetrievalRequest;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则降级 Reranker（P1 默认）：保持 FusionMerger 规则排序（verified→confidence→来源优先级）。
 *
 * <p>当 bge-reranker 未启用/不可达时作为兜底，返回原顺序副本，保证主流程数据结构一致。</p>
 */
@Slf4j
@Component
@ConditionalOnMissingBean(BgeReranker.class)
public class RuleFallbackReranker implements RetrievalReranker {

    @Override
    public List<RetrievalHit> rerank(RetrievalRequest request, List<RetrievalHit> hits) {
        if (hits == null) {
            return new ArrayList<>();
        }
        log.debug("retrieval: Rerank 规则降级生效，保持规则排序 hits={}", hits.size());
        return new ArrayList<>(hits);
    }
}
