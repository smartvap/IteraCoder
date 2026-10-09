package com.agenthub.ai.rcagent.attribution.component;

import com.agenthub.ai.rcagent.attribution.adapter.KnowledgeSourceAdapter;
import com.agenthub.ai.rcagent.attribution.model.MetricKnowledgeSnapshot;
import org.springframework.stereotype.Component;

/**
 * 指标口径/概念解析（MetricKnowledgeResolver，BR-001 口径冻结）。
 *
 * <p>经 {@link KnowledgeSourceAdapter}（module-004 只读 verified/active）解析 kb_metric 口径，
 * 归因发起时冻结 metric_knowledge_json 快照，后续下钻与结论引用发起时口径。</p>
 */
@Component
public class MetricKnowledgeResolver {

    private final KnowledgeSourceAdapter knowledgeSourceAdapter;

    public MetricKnowledgeResolver(KnowledgeSourceAdapter knowledgeSourceAdapter) {
        this.knowledgeSourceAdapter = knowledgeSourceAdapter;
    }

    /**
     * 解析并冻结指标口径快照。
     *
     * @return 口径快照（无 verified 命中时 verified=0，BR-001 降级）
     */
    public MetricKnowledgeSnapshot resolve(String dsId, String metricName, Long metricKbId) {
        return knowledgeSourceAdapter.resolveMetric(dsId, metricName, metricKbId);
    }

    /** 重新检索最新口径版本（口径变更检测） */
    public MetricKnowledgeSnapshot resolveLatest(String dsId, String metricName) {
        return knowledgeSourceAdapter.resolveLatestMetric(dsId, metricName);
    }
}
