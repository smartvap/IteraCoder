package com.agenthub.ai.rcagent.retrieval.support;

import com.agenthub.ai.rcagent.retrieval.config.RetrievalProperties;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalSourceType;
import com.agenthub.ai.rcagent.retrieval.dto.QueryElements;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalRequest;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 冷启动检测（ColdStartDetector，FR-BE-006 / BR-002）。
 *
 * <p>关键要素（表/指标等）无 verified 高置信命中时返回 cold_start=true + 缺失项清单 + reason；
 * cold_start_allowed 仅作为日志/下游信号，本模块不生成 SQL。</p>
 */
@Component
public class ColdStartDetector {

    /** 判定高置信所需的最低 verified/status 组合见 {@code isHighConfident} */
    private static final double DEFAULT_HIGH_CONFIDENCE = 0.6;

    private final RetrievalProperties properties;

    public ColdStartDetector(RetrievalProperties properties) {
        this.properties = properties;
    }

    /**
     * 冷启动检测。
     *
     * @param request 检索请求（query_elements 提供关键要素）
     * @param items   最终统一命中（已排序去重）
     * @return 冷启动结果（cold + reason + 缺失项清单）
     */
    public ColdStartResult detect(RetrievalRequest request, List<RetrievalHit> items) {
        ColdStartResult result = new ColdStartResult();
        QueryElements elements = request.getQueryElements();
        List<String> keyTerms = new ArrayList<>();
        if (elements != null) {
            keyTerms.addAll(nonNull(elements.getMetrics()));
            keyTerms.addAll(nonNull(elements.getConcepts()));
            keyTerms.addAll(nonNull(elements.getDimensions()));
            keyTerms.addAll(nonNull(elements.getEntities()));
        }

        // 无关键要素：不冷启动（返回 false，覆盖为空列表）
        if (keyTerms.isEmpty()) {
            result.setColdStart(false);
            result.setMissing(new ArrayList<>());
            result.setReason(null);
            return result;
        }

        List<String> missing = new ArrayList<>();
        for (String term : keyTerms) {
            if (!hasHighConfidentHit(items, term)) {
                missing.add(term);
            }
        }
        boolean cold = !missing.isEmpty();
        result.setColdStart(cold);
        result.setMissing(missing);
        if (cold) {
            result.setReason("关键要素无高置信命中: " + String.join("、", missing)
                    + (request.coldStartAllowedValue() ? "（已允许冷生成降级，请补充口径后重试）"
                    : "（cold_start_allowed=false，下游应提示信息不足）"));
        }
        return result;
    }

    /** 是否存在 verified=1 高置信命中（title/content 包含要素词） */
    private boolean hasHighConfidentHit(List<RetrievalHit> items, String term) {
        if (items == null || term == null || term.isBlank()) {
            return false;
        }
        double threshold = properties.highConfidenceMinValue() <= 0
                ? DEFAULT_HIGH_CONFIDENCE : properties.highConfidenceMinValue();
        String needle = term.toLowerCase();
        for (RetrievalHit hit : items) {
            if (hit.getVerified() == null || hit.getVerified() != 1) {
                continue;
            }
            if (hit.getConfidence() == null
                    || hit.getConfidence().doubleValue() < threshold) {
                continue;
            }
            // A 库结构化命中才算口径高置信（B 文档仅辅助）
            if (!isKbAStructured(hit)) {
                continue;
            }
            String title = hit.getTitle() == null ? "" : hit.getTitle().toLowerCase();
            String content = hit.getContent() == null ? "" : hit.getContent().toLowerCase();
            if (title.contains(needle) || content.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean isKbAStructured(RetrievalHit hit) {
        RetrievalSourceType source = hit.getSource();
        if (source == null) {
            return false;
        }
        return source == RetrievalSourceType.A_SCHEMA_TABLE
                || source == RetrievalSourceType.A_SCHEMA_COLUMN
                || source == RetrievalSourceType.A_METRIC
                || source == RetrievalSourceType.A_CONCEPT
                || source == RetrievalSourceType.A_TEMPLATE;
    }

    private List<String> nonNull(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }

    /** 冷启动检测结果（进程内使用，不直接作为 VO） */
    public static class ColdStartResult {
        private boolean coldStart;
        private List<String> missing = new ArrayList<>();
        private String reason;

        public boolean isColdStart() {
            return coldStart;
        }

        public void setColdStart(boolean coldStart) {
            this.coldStart = coldStart;
        }

        public List<String> getMissing() {
            return missing;
        }

        public void setMissing(List<String> missing) {
            this.missing = missing;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
