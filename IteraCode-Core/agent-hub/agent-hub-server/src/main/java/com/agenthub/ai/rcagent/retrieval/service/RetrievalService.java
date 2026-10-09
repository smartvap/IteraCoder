package com.agenthub.ai.rcagent.retrieval.service;

import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.rcagent.retrieval.config.RetrievalProperties;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalChannel;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalElementType;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalErrorCode;
import com.agenthub.ai.rcagent.retrieval.dto.QueryElements;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalLogPageQuery;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalRequest;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalStatsQuery;
import com.agenthub.ai.rcagent.retrieval.entity.RcagentRetrievalLog;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalContextVO;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalCoverage;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalMeta;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalResult;
import com.agenthub.ai.rcagent.retrieval.rerank.RetrievalReranker;
import com.agenthub.ai.rcagent.retrieval.support.ColdStartDetector;
import com.agenthub.ai.rcagent.retrieval.support.ConceptResolveResult;
import com.agenthub.ai.rcagent.retrieval.support.FusionMerger;
import com.agenthub.ai.rcagent.retrieval.support.KbARetrieval;
import com.agenthub.ai.rcagent.retrieval.support.KnowledgeConceptResolver;
import com.agenthub.ai.rcagent.retrieval.support.LlmWikiBSearcher;
import com.agenthub.ai.rcagent.retrieval.support.RetrievalContextAssembler;
import com.agenthub.ai.rcagent.retrieval.support.RetrievalSpaceResolver;
import com.agenthub.ai.rcagent.retrieval.vo.RetrievalStatsVO;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 双重知识库融合检索主服务（RetrievalService，api-contract 内部服务契约）。
 *
 * <p>主编排（FR-BE-001~008）：参数校验 → 概念/指标回填 KnowledgeConceptResolver
 * → A 五路 KbARetrieval/RetrievalAdapter → B 路 LlmWikiBSearcher（BR-003 可降级）
 * → FusionMerger 合并去重排序 →（可选 Rerank）→ RetrievalContextAssembler
 * → ColdStartDetector → 落观测日志 RcagentRetrievalLogService。
 * 检索为纯程序节点，不调用 LLM 生成/意图识别（意图归 workflow-engine，spec C.4）。</p>
 */
@Slf4j
@Service
public class RetrievalService {

    private final KnowledgeConceptResolver conceptResolver;
    private final KbARetrieval kbARetrieval;
    private final LlmWikiBSearcher wikiBSearcher;
    private final RetrievalSpaceResolver spaceResolver;
    private final FusionMerger fusionMerger;
    private final RetrievalContextAssembler contextAssembler;
    private final ColdStartDetector coldStartDetector;
    private final RcagentRetrievalLogService logService;
    private final RetrievalProperties properties;
    private final List<RetrievalReranker> rerankers;

    public RetrievalService(KnowledgeConceptResolver conceptResolver,
                            KbARetrieval kbARetrieval,
                            LlmWikiBSearcher wikiBSearcher,
                            RetrievalSpaceResolver spaceResolver,
                            FusionMerger fusionMerger,
                            RetrievalContextAssembler contextAssembler,
                            ColdStartDetector coldStartDetector,
                            RcagentRetrievalLogService logService,
                            RetrievalProperties properties,
                            List<RetrievalReranker> rerankers) {
        this.conceptResolver = conceptResolver;
        this.kbARetrieval = kbARetrieval;
        this.wikiBSearcher = wikiBSearcher;
        this.spaceResolver = spaceResolver;
        this.fusionMerger = fusionMerger;
        this.contextAssembler = contextAssembler;
        this.coldStartDetector = coldStartDetector;
        this.logService = logService;
        this.properties = properties;
        this.rerankers = rerankers;
    }

    /** 接口1/内部契约：融合检索主入口 */
    public RetrievalResult search(RetrievalRequest request) {
        long start = System.currentTimeMillis();
        validateRequest(request);

        RetrievalResult result = new RetrievalResult();
        result.setRequestId(genRequestId());
        result.setDsId(request.getDsId());

        List<String> degradations = new ArrayList<>();
        List<RetrievalHit> all = new ArrayList<>();
        Map<String, Integer> channelHits = new LinkedHashMap<>();
        for (RetrievalChannel c : RetrievalChannel.values()) {
            channelHits.put(c.getValue(), 0);
        }

        // 1. 概念/指标回填（FR-BE-001）：命中 kb_business_concept/kb_metric 回填 related_*
        ConceptResolveResult concept = request.enableKbAValue() ? conceptResolver.resolve(request)
                : new ConceptResolveResult();

        // 2. A 库五路结构化召回（FR-BE-002，schema/template/biz-link/relation/semantic）
        if (request.enableKbAValue()) {
            String keyword = buildKeyword(request);
            KbARetrieval.AOutcome aOutcome = kbARetrieval.searchA(
                    request.getDsId(), keyword, concept.getRelatedTables(), concept.getRelatedColumns(),
                    request.getQueryElements() == null ? null : request.getQueryElements().getKeywords(),
                    request.includeDraftValue(), request.perChannelTopKValue(), concept.getSemanticHits());
            aOutcome.getChannelHits().forEach((channel, count) ->
                    channelHits.merge(channel, count == null ? 0 : count, Integer::sum));
            all.addAll(aOutcome.getHits());
        }

        // 3. B 库 llm-wiki 文档语义召回（FR-BE-003，BR-003 弱可用降级）
        String spaceUsed = null;
        boolean kbBUnavailable = false;
        if (request.enableKbBValue()) {
            int before = degradations.size();
            // 空间解析优先级：显式 spaceName → ds 注册表 space_name → A 库语义命中 space_name
            String explicitSpace = request.spaceNameValue();
            String dsSpace = spaceResolver.resolveSpaceName(request.getDsId());
            String semanticSpace = concept.getSpaceName();
            String space = explicitSpace != null && !explicitSpace.isBlank()
                    ? explicitSpace
                    : (dsSpace != null && !dsSpace.isBlank() ? dsSpace : semanticSpace);
            List<RetrievalHit> wikiHits = wikiBSearcher.search(explicitSpace, request.getDsId(),
                    semanticSpace, buildBQuery(request), request.perChannelTopKValue(), degradations);
            spaceUsed = wikiHits.isEmpty() ? null : space;
            // degrade 新增（含空间不可解析/空/服务异常）→ B 路降级标志
            if (degradations.size() > before || wikiHits.isEmpty()) {
                if (degradations.size() == before && wikiHits.isEmpty()) {
                    degradations.add("llm-wiki 空间无匹配文档，已跳过知识库 B 召回");
                }
                kbBUnavailable = true;
            } else {
                channelHits.merge(RetrievalChannel.WIKI_DOCS.getValue(), wikiHits.size(), Integer::sum);
                all.addAll(wikiHits);
            }
        }

        // 4. 合并去重 + 冲突标记 + 排序（FR-BE-004）
        List<RetrievalHit> merged = fusionMerger.merge(request.getDsId(), all, request.topKValue());

        // 4.1 filters.confidence_min 阈值过滤（契约：最小置信度；include_draft 调试/审核场景豁免，
        //     让草稿/低置信知识仍可观测，避免调试语义被阈值误伤）
        merged = filterByConfidenceMin(request, merged);

        // 5. 可选 Rerank（FR-BE-008，P2 bge / P1 规则降级）
        if (request.rerankEnabledValue()) {
            RetrievalReranker reranker = selectReranker();
            if (reranker != null) {
                merged = reranker.rerank(request, merged);
            }
        }

        // 6. 统一上下文组装（FR-BE-005，分组 + 裁剪）
        RetrievalContextVO context = contextAssembler.assemble(merged);

        // 7. 冷启动检测（FR-BE-006）
        ColdStartDetector.ColdStartResult coldResult = coldStartDetector.detect(request, merged);

        // 覆盖率（coverage.matched/missing）
        RetrievalCoverage coverage = buildCoverage(request, merged);

        // 8. 元信息
        RetrievalMeta meta = buildMeta(channelHits, context.getItems(), spaceUsed,
                System.currentTimeMillis() - start);

        result.setContext(context);
        result.setItems(context.getItems());
        result.setCoverage(coverage);
        result.setColdStart(coldResult.isColdStart());
        result.setColdStartReason(coldResult.getReason());
        result.setDegradations(degradations);
        result.setMeta(meta);

        // 9. 落观测日志（失败不阻塞，logService 内部 try/catch）
        recordLog(request, result, coldResult, kbBUnavailable, spaceUsed);

        log.info("retrieval: search done requestId={} dsId={} items={} latencyMs={}",
                result.getRequestId(), request.getDsId(), result.getItems().size(), meta.getLatencyMs());
        return result;
    }

    /** 接口2：质量日志分页 */
    public PageResult pageLogs(RetrievalLogPageQuery query) {
        return logService.pageLogs(query);
    }

    /** 接口3：质量指标聚合（FR-007 P2） */
    public RetrievalStatsVO stats(RetrievalStatsQuery query) {
        return logService.stats(query);
    }

    // ============ 内部组装 ============

    private void validateRequest(RetrievalRequest request) {
        ThrowUtils.throwIf(request == null,
                new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数不能为空"));
        ThrowUtils.throwIf(request.getDsId() == null || request.getDsId().isBlank(),
                new BusinessException(RetrievalErrorCode.DS_ID_MISSING, "ds_id 不能为空"));
        ThrowUtils.throwIf(request.getDsId().length() > 64,
                new BusinessException(RetrievalErrorCode.DS_ID_MISSING, "ds_id 格式非法"));
        boolean hasQuestion = request.getQuestion() != null && !request.getQuestion().isBlank();
        boolean hasElements = request.hasElements();
        ThrowUtils.throwIf(!hasQuestion && !hasElements,
                new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "question 不能为空"));
        if (request.getTopK() != null) {
            ThrowUtils.throwIf(request.getTopK() < 1 || request.getTopK() > 100,
                    new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "top_k 需在 1~100 之间"));
        }
        if (request.getPerChannelTopK() != null) {
            ThrowUtils.throwIf(request.getPerChannelTopK() < 1 || request.getPerChannelTopK() > 50,
                    new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "per_channel_top_k 需在 1~50 之间"));
        }
        String status = request.filtersValue().getStatus();
        if (status != null && !status.isBlank()) {
            ThrowUtils.throwIf(!List.of("draft", "active", "deprecated").contains(status),
                    new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "status 非法"));
        }
        // filters.confidence_min 取值范围 0~1（契约最小置信度，校验层先约束）
        BigDecimal confidenceMin = request.confidenceMinValue();
        if (confidenceMin != null) {
            ThrowUtils.throwIf(confidenceMin.compareTo(BigDecimal.ZERO) < 0
                            || confidenceMin.compareTo(BigDecimal.ONE) > 0,
                    new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL,
                            "confidence_min 需在 0~1 之间"));
        }
    }

    /** 按 filters.confidence_min 过滤低于阈值的命中（非空时生效；include_draft 调试/审核场景豁免） */
    private List<RetrievalHit> filterByConfidenceMin(RetrievalRequest request, List<RetrievalHit> merged) {
        BigDecimal min = request.confidenceMinValue();
        if (min == null || request.includeDraftValue() || merged == null || merged.isEmpty()) {
            return merged;
        }
        List<RetrievalHit> filtered = new ArrayList<>(merged.size());
        for (RetrievalHit hit : merged) {
            if (hit == null || hit.getConfidence() == null || hit.getConfidence().compareTo(min) < 0) {
                continue;
            }
            filtered.add(hit);
        }
        return filtered;
    }

    /**
     * A 路主检索关键词：要素词优先，缺省用 question。
     *
     * <p>取词优先级：{@code query_elements.keywords} 非空时**只**用 keywords，并取其中**最长的一个
     * token**——keywords 是上游抽出的标识符 token（表名/字段名），A 路 schema LIKE 必须保持单子串；
     * 若直接拼接多 token（{@code LIKE '%a b c%'}）会失配，故只取最长者可命中者作为主关键词。
     * keywords 为空时维持 metrics→concepts→dimensions→entities 顺序；全部为空再回退 question。
     * 纯中文语义词（entities）不参与 keyword，列级召回由 RetrievalAdapter 的「命中表列展开」覆盖。</p>
     */
    private String buildKeyword(RetrievalRequest request) {
        QueryElements qe = request.getQueryElements();
        String keyword = null;
        if (qe != null) {
            if (qe.getKeywords() != null && !qe.getKeywords().isEmpty()) {
                // 单 token 优先：从多 token 中取最长者，保证 A 路 schema LIKE 可用
                keyword = longestTerm(qe.getKeywords());
            } else {
                StringBuilder sb = new StringBuilder();
                appendTerms(sb, qe.getMetrics());
                appendTerms(sb, qe.getConcepts());
                appendTerms(sb, qe.getDimensions());
                appendTerms(sb, qe.getEntities());
                keyword = sb.toString().trim();
            }
        }
        if (keyword == null || keyword.isEmpty()) {
            if (request.getQuestion() == null) {
                return "";
            }
            keyword = request.getQuestion().trim();
        }
        return keyword.length() > 200 ? keyword.substring(0, 200) : keyword;
    }

    /** 取最长的一个非空词（并列取首次出现）；无有效词返回 null */
    private String longestTerm(List<String> terms) {
        String best = null;
        for (String t : terms) {
            if (t == null || t.isBlank()) {
                continue;
            }
            String term = t.trim();
            if (best == null || term.length() > best.length()) {
                best = term;
            }
        }
        return best;
    }

    /** B 路检索文本：问题优先，缺省用要素词拼接 */
    private String buildBQuery(RetrievalRequest request) {
        if (request.getQuestion() != null && !request.getQuestion().isBlank()) {
            String q = request.getQuestion().trim();
            return q.length() > 300 ? q.substring(0, 300) : q;
        }
        return buildKeyword(request);
    }

    private void appendTerms(StringBuilder sb, List<String> terms) {
        if (terms == null) {
            return;
        }
        for (String t : terms) {
            if (t == null || t.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t.trim());
        }
    }

    private RetrievalReranker selectReranker() {
        if (rerankers == null || rerankers.isEmpty()) {
            return null;
        }
        return rerankers.get(0);
    }

    /** 覆盖率：对 query_elements 每类词判断命中（title/content 包含匹配，仅观测/日志用） */
    private RetrievalCoverage buildCoverage(RetrievalRequest request, List<RetrievalHit> items) {
        RetrievalCoverage coverage = new RetrievalCoverage();
        QueryElements qe = request.getQueryElements();
        if (qe == null) {
            return coverage;
        }
        checkTerms(coverage, items, RetrievalElementType.METRIC, qe.getMetrics());
        checkTerms(coverage, items, RetrievalElementType.CONCEPT, qe.getConcepts());
        checkTerms(coverage, items, RetrievalElementType.DIMENSION, qe.getDimensions());
        checkTerms(coverage, items, RetrievalElementType.ENTITY, qe.getEntities());
        checkTerms(coverage, items, RetrievalElementType.KEYWORD, qe.getKeywords());
        if (qe.getTimeRange() != null) {
            String start = qe.getTimeRange().getStart();
            String end = qe.getTimeRange().getEnd();
            String term = start == null ? (end == null ? "时间范围" : end) : (end == null ? start : start + "~" + end);
            if (containsTerm(items, term)) {
                coverage.getMatched().add(RetrievalCoverage.RetrievalElementHit.of(
                        RetrievalElementType.TIME_RANGE, term));
            } else {
                coverage.getMissing().add(RetrievalCoverage.RetrievalElementHit.of(
                        RetrievalElementType.TIME_RANGE, term));
            }
        }
        return coverage;
    }

    private void checkTerms(RetrievalCoverage coverage, List<RetrievalHit> items,
                            RetrievalElementType type, List<String> terms) {
        if (terms == null) {
            return;
        }
        for (String term : terms) {
            if (term == null || term.isBlank()) {
                continue;
            }
            if (containsTerm(items, term)) {
                coverage.getMatched().add(RetrievalCoverage.RetrievalElementHit.of(type, term.trim()));
            } else {
                coverage.getMissing().add(RetrievalCoverage.RetrievalElementHit.of(type, term.trim()));
            }
        }
    }

    private boolean containsTerm(List<RetrievalHit> items, String term) {
        if (items == null || term == null || term.isBlank()) {
            return false;
        }
        String needle = term.toLowerCase();
        for (RetrievalHit hit : items) {
            String title = hit.getTitle() == null ? "" : hit.getTitle().toLowerCase();
            String content = hit.getContent() == null ? "" : hit.getContent().toLowerCase();
            if (title.contains(needle) || content.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private RetrievalMeta buildMeta(Map<String, Integer> channelHits, List<RetrievalHit> contextItems,
                                    String spaceUsed, long latencyMs) {
        RetrievalMeta meta = new RetrievalMeta();
        meta.setChannelHits(channelHits);
        meta.setContextItemCount(contextItems == null ? 0 : contextItems.size());
        meta.setContextTokenEstimate(estimateTokens(contextItems));
        meta.setSpaceUsed(spaceUsed);
        meta.setLatencyMs(latencyMs);
        return meta;
    }

    /** 粗 token 估算：正文总字符数/2（中文为主场景的近似），防 token 膨胀观测 */
    private int estimateTokens(List<RetrievalHit> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int chars = 0;
        for (RetrievalHit hit : items) {
            if (hit.getContent() != null) {
                chars += hit.getContent().length();
            }
        }
        return chars / 2;
    }

    private void recordLog(RetrievalRequest request, RetrievalResult result,
                           ColdStartDetector.ColdStartResult coldResult,
                           boolean kbBUnavailable, String spaceUsed) {
        try {
            RcagentRetrievalLog log = new RcagentRetrievalLog();
            log.setDsId(request.getDsId());
            log.setThreadId(request.getThreadId());
            // 落库前截断 question（AGENTS.md 5.3），query_elements/matched/missing/channel 用 JSON 文本
            String question = request.getQuestion();
            log.setQuestion(question == null ? "" : (question.length() > 500 ? question.substring(0, 500) : question));
            if (request.getQueryElements() != null) {
                log.setQueryElementsJson(JSON.toJSONString(request.getQueryElements()));
            }
            if (result.getCoverage() != null) {
                log.setMatchedElementsJson(JSON.toJSONString(result.getCoverage().getMatched()));
                log.setMissingElementsJson(JSON.toJSONString(result.getCoverage().getMissing()));
            }
            if (result.getMeta() != null && result.getMeta().getChannelHits() != null) {
                log.setChannelHitJson(JSON.toJSONString(result.getMeta().getChannelHits()));
            }
            log.setContextItemCount(result.getMeta() == null ? 0 : result.getMeta().getContextItemCount());
            log.setContextTokenEstimate(result.getMeta() == null ? 0 : result.getMeta().getContextTokenEstimate());
            log.setColdStart(result.isColdStart() ? 1 : 0);
            log.setAllowColdStart(request.coldStartAllowedValue() ? 1 : 0);
            log.setSpaceUsed(spaceUsed);
            log.setKbBUnavailable(kbBUnavailable ? 1 : 0);
            log.setRerankEnabled(request.rerankEnabledValue() ? 1 : 0);
            log.setLatencyMs(result.getMeta() == null ? 0 : result.getMeta().getLatencyMs().intValue());
            logService.record(log);
        } catch (Exception e) {
            log.warn("retrieval: 组装检索观测日志异常(忽略): {}", e.getMessage());
        }
    }

    private String genRequestId() {
        return "req_" + System.currentTimeMillis() + "_" + ThreadLocalRandom.current().nextInt(1000, 9999);
    }
}
