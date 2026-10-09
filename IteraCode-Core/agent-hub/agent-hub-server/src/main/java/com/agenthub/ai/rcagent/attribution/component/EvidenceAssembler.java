package com.agenthub.ai.rcagent.attribution.component;

import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionQueryLog;
import com.agenthub.ai.rcagent.attribution.vo.EvidenceChainItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 证据组装（EvidenceAssembler，FR-006）。
 *
 * <p>每条结论/因子关联其证据 SQL（sql_abstract）与 query_id 证据链；
 * 证据展示仅输出脱敏抽象，不输出 SQL 原文（AGENTS.md 5.x）。</p>
 */
@Component
public class EvidenceAssembler {

    /**
     * 组装证据链。
     *
     * @param dimension   支撑维度（因子维度）
     * @param queryIds    证据查询 id（factor.evidence_query_ids / node.query_ids）
     * @param logIndex    query_id → 归因查询日志（含 sql_abstract）
     */
    public List<EvidenceChainItem> assemble(String dimension, List<String> queryIds,
                                            Map<String, RcagentAttributionQueryLog> logIndex) {
        List<EvidenceChainItem> chain = new ArrayList<>();
        if (queryIds == null || queryIds.isEmpty() || logIndex == null) {
            return chain;
        }
        for (String queryId : queryIds) {
            if (queryId == null || queryId.isBlank()) {
                continue;
            }
            RcagentAttributionQueryLog log = logIndex.get(queryId);
            if (log == null) {
                continue;
            }
            EvidenceChainItem item = new EvidenceChainItem();
            item.setQueryId(log.getQueryId());
            item.setQueryType(log.getQueryType());
            item.setSqlAbstract(log.getSqlAbstract());
            item.setDimension(dimension);
            chain.add(item);
        }
        return chain;
    }
}
