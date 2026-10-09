package com.agenthub.ai.rcagent.attribution.adapter;

import com.agenthub.ai.rcagent.attribution.model.MetricKnowledgeSnapshot;
import com.agenthub.ai.rcagent.kb.dto.KbSemanticRetrievalDTO;
import com.agenthub.ai.rcagent.kb.service.KbRetrievalService;
import com.agenthub.ai.rcagent.kb.vo.KbMetricVO;
import com.agenthub.ai.rcagent.kb.vo.KbSemanticRetrievalVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 指标口径知识只读适配（KnowledgeSourceAdapter，BR-001）。
 *
 * <p>进程内调用 module-004 {@link KbRetrievalService#retrieveSemantic}（只读，检索实现强制
 * verified=1 AND status=active），按 metric_name 命中 kb_metric 并冻结口径快照；
 * 无口径命中返回 verified=0 快照（降级信号，不抛错）；依赖检索异常收敛为空快照并提示（44105 由上层按需抛出）。</p>
 */
@Slf4j
@Component
public class KnowledgeSourceAdapter {

    /** semantic 检索 topK（指标名匹配候选数上限） */
    private static final int SEMANTIC_TOP_K = 20;

    private final KbRetrievalService kbRetrievalService;

    public KnowledgeSourceAdapter(KbRetrievalService kbRetrievalService) {
        this.kbRetrievalService = kbRetrievalService;
    }

    /**
     * 按指标名解析 kb_metric 口径快照。
     *
     * @return 口径快照；无 verified/active 命中时 kbMetricId=null/verified=0（BR-001 降级）
     */
    public MetricKnowledgeSnapshot resolveMetric(String dsId, String metricName, Long metricKbId) {
        MetricKnowledgeSnapshot empty = new MetricKnowledgeSnapshot();
        empty.setMetricName(metricName);
        empty.setVerified(0);
        if (dsId == null || dsId.isBlank() || metricName == null || metricName.isBlank()) {
            return empty;
        }
        try {
            KbSemanticRetrievalDTO dto = new KbSemanticRetrievalDTO();
            dto.setDsId(dsId);
            dto.setCategory("METRIC");
            dto.setKeyword(metricName.trim());
            dto.setTopK(SEMANTIC_TOP_K);
            KbSemanticRetrievalVO vo = kbRetrievalService.retrieveSemantic(dto);
            if (vo == null || vo.getMetrics() == null || vo.getMetrics().isEmpty()) {
                return empty;
            }
            KbMetricVO hit = pickBest(vo.getMetrics(), metricName, metricKbId);
            if (hit == null) {
                return empty;
            }
            return toSnapshot(hit, metricName);
        } catch (RuntimeException e) {
            log.warn("attribution: 指标口径检索失败(按空口径降级): {}", e.getMessage());
            return empty;
        }
    }

    /**
     * 重新检索最新口径（口径变更检测用，QualityIssueDetector）。
     */
    public MetricKnowledgeSnapshot resolveLatestMetric(String dsId, String metricName) {
        return resolveMetric(dsId, metricName, null);
    }

    private KbMetricVO pickBest(List<KbMetricVO> metrics, String metricName, Long metricKbId) {
        List<KbMetricVO> list = new ArrayList<>(metrics);
        // verified/active 已由 kb 检索保证；本地二次防御，防止未来检索语义放宽
        list.removeIf(m -> !Integer.valueOf(1).equals(m.getVerified()) || !"active".equals(m.getStatus()));
        if (list.isEmpty()) {
            return null;
        }
        // 显式 kb id 优先
        if (metricKbId != null) {
            for (KbMetricVO m : list) {
                if (metricKbId.equals(m.getId())) {
                    return m;
                }
            }
        }
        // 精确名优先，其次前缀，最后 confidence
        list.sort(Comparator.comparingInt((KbMetricVO m) -> matchScore(m, metricName)).reversed()
                .thenComparing(m -> m.getConfidence() == null ? java.math.BigDecimal.ZERO : m.getConfidence(),
                        Comparator.reverseOrder()));
        return list.get(0);
    }

    private int matchScore(KbMetricVO metric, String metricName) {
        String name = metric.getMetricName();
        if (name == null) {
            return 0;
        }
        if (name.trim().equalsIgnoreCase(metricName.trim())) {
            return 100;
        }
        if (name.trim().toLowerCase(Locale.ROOT).contains(metricName.trim().toLowerCase(Locale.ROOT))) {
            return 60;
        }
        return 10;
    }

    private MetricKnowledgeSnapshot toSnapshot(KbMetricVO metric, String fallbackName) {
        MetricKnowledgeSnapshot snapshot = new MetricKnowledgeSnapshot();
        snapshot.setKbMetricId(metric.getId());
        snapshot.setVersion(metric.getVersion());
        snapshot.setMetricName(metric.getMetricName() == null ? fallbackName : metric.getMetricName());
        snapshot.setDefinition(metric.getDefinition());
        snapshot.setCalculationRule(metric.getCalculationRule());
        snapshot.setRelatedTables(MetricKnowledgeSnapshot.parseStringList(metric.getRelatedTables()));
        snapshot.setRelatedColumns(MetricKnowledgeSnapshot.parseStringList(metric.getRelatedColumns()));
        snapshot.setSpaceName(metric.getSpaceName());
        snapshot.setConfidence(metric.getConfidence());
        snapshot.setVerified(Integer.valueOf(1).equals(metric.getVerified()) ? 1 : 0);
        snapshot.setEvidence(metric.getEvidence());
        return snapshot;
    }
}
