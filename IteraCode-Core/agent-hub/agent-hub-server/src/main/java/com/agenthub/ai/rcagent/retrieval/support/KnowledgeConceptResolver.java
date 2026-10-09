package com.agenthub.ai.rcagent.retrieval.support;

import com.agenthub.ai.rcagent.kb.dto.KbSemanticRetrievalDTO;
import com.agenthub.ai.rcagent.kb.service.KbRetrievalService;
import com.agenthub.ai.rcagent.kb.vo.KbConceptVO;
import com.agenthub.ai.rcagent.kb.vo.KbMetricVO;
import com.agenthub.ai.rcagent.kb.vo.KbSemanticRetrievalVO;
import com.agenthub.ai.rcagent.retrieval.config.RetrievalProperties;
import com.agenthub.ai.rcagent.retrieval.dto.QueryElements;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalRequest;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 业务概念/指标识别与回填（KnowledgeConceptResolver，FR-001）。
 *
 * <p>检索前对 query_elements.concepts/metrics（以及 question 兜底）做 A 库 semantic 召回，
 * 命中 kb_business_concept/kb_metric 后解析 related_tables/related_columns/related_sql_ids，
 * 作为后续 schema/template/biz-link/relation 各路召回的追加检索条件（放大召回覆盖面）；
 * 同时返回语义路命中统一条目供 A 五路直接并入，避免二次检索。</p>
 */
@Slf4j
@Component
public class KnowledgeConceptResolver {

    private final KbRetrievalService kbRetrievalService;
    private final RetrievalAdapter retrievalAdapter;
    private final RetrievalProperties properties;

    public KnowledgeConceptResolver(KbRetrievalService kbRetrievalService,
                                    RetrievalAdapter retrievalAdapter,
                                    RetrievalProperties properties) {
        this.kbRetrievalService = kbRetrievalService;
        this.retrievalAdapter = retrievalAdapter;
        this.properties = properties;
    }

    /**
     * 概念/指标回填（语义命中失败不阻断主链路，仅记录）。
     */
    public ConceptResolveResult resolve(RetrievalRequest request) {
        ConceptResolveResult result = new ConceptResolveResult();
        if (request == null || request.getDsId() == null || request.getDsId().isBlank()) {
            return result;
        }
        try {
            QueryElements elements = request.getQueryElements();
            List<String> candidates = collectCandidates(elements);
            String keyword = joinKeywords(candidates, request.getQuestion());
            if (keyword == null || keyword.isBlank()) {
                return result;
            }
            KbSemanticRetrievalDTO dto = new KbSemanticRetrievalDTO();
            dto.setDsId(request.getDsId());
            dto.setKeyword(keyword);
            dto.setCategory("ALL");
            dto.setTopK(request.perChannelTopKValue());
            KbSemanticRetrievalVO vo = kbRetrievalService.retrieveSemantic(dto);
            if (vo == null) {
                return result;
            }
            // 解析回填 related_*（概念与指标两类来源）
            Set<String> tables = new HashSet<>();
            Set<String> columns = new HashSet<>();
            Set<String> sqlIds = new HashSet<>();
            Set<String> spaceNames = new HashSet<>();
            if (vo.getMetrics() != null) {
                for (KbMetricVO m : vo.getMetrics()) {
                    tables.addAll(parseStringList(m.getRelatedTables()));
                    columns.addAll(parseStringList(m.getRelatedColumns()));
                    if (m.getSpaceName() != null && !m.getSpaceName().isBlank()) {
                        spaceNames.add(m.getSpaceName());
                    }
                }
            }
            if (vo.getConcepts() != null) {
                for (KbConceptVO c : vo.getConcepts()) {
                    tables.addAll(parseStringList(c.getRelatedTables()));
                    columns.addAll(parseStringList(c.getRelatedColumns()));
                    sqlIds.addAll(parseStringList(c.getRelatedSqlIds()));
                    if (c.getSpaceName() != null && !c.getSpaceName().isBlank()) {
                        spaceNames.add(c.getSpaceName());
                    }
                }
            }
            result.setRelatedTables(new ArrayList<>(tables));
            result.setRelatedColumns(new ArrayList<>(columns));
            result.setRelatedSqlIds(new ArrayList<>(sqlIds));
            result.setSpaceName(spaceNames.isEmpty() ? null : spaceNames.iterator().next());
            // 语义路命中统一条目（供 A 五路复用）
            List<RetrievalHit> semanticHits = retrievalAdapter.semanticHitsOf(vo);
            result.setSemanticHits(semanticHits == null ? new ArrayList<>() : semanticHits);
        } catch (Exception e) {
            log.warn("retrieval: 概念/指标回填失败，降级为空回填: {}", e.getMessage());
        }
        return result;
    }

    /** 概念/指标回填关键词候选：concepts + metrics + entities + dimensions + keywords + question */
    private List<String> collectCandidates(QueryElements elements) {
        List<String> list = new ArrayList<>();
        if (elements == null) {
            return list;
        }
        addAll(list, elements.getConcepts());
        addAll(list, elements.getMetrics());
        addAll(list, elements.getEntities());
        addAll(list, elements.getDimensions());
        addAll(list, elements.getKeywords());
        return list;
    }

    private void addAll(List<String> target, List<String> source) {
        if (source != null) {
            target.addAll(source);
        }
    }

    private String joinKeywords(List<String> candidates, String question) {
        StringBuilder sb = new StringBuilder();
        if (candidates != null) {
            for (String c : candidates) {
                if (c != null && !c.isBlank()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(c.trim());
                }
            }
        }
        // 候选词为空时以 question 兜底（若过长截断避免无意义全量模糊）
        if (sb.length() == 0 && question != null && !question.isBlank()) {
            String q = question.trim();
            sb.append(q.length() > 50 ? q.substring(0, 50) : q);
        }
        return sb.toString();
    }

    /** JSON 数组文本 → List<String>（related_tables/related_columns/related_sql_ids 均为 JSON 数组） */
    private static List<String> parseStringList(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            JSONArray arr = JSON.parseArray(json);
            if (arr == null) {
                return out;
            }
            for (Object o : arr) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } catch (Exception ignored) {
            log.debug("retrieval: related JSON 解析失败: {}", json.length() > 100 ? json.substring(0, 100) : json);
        }
        return out;
    }
}
