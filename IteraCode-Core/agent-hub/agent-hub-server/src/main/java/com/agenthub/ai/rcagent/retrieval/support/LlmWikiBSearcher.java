package com.agenthub.ai.rcagent.retrieval.support;

import com.agenthub.ai.llmwiki.client.LlmWikiClient;
import com.agenthub.ai.llmwiki.client.LlmWikiClientException;
import com.agenthub.ai.llmwiki.dto.SearchHitVO;
import com.agenthub.ai.llmwiki.dto.SearchRequest;
import com.agenthub.ai.rcagent.retrieval.config.RetrievalProperties;
import com.agenthub.ai.rcagent.retrieval.constant.ConflictFlag;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalChannel;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalSourceType;
import com.agenthub.ai.rcagent.retrieval.model.EvidenceRef;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 知识库 B（llm-wiki）语义召回封装（BR-003 弱可用性）。
 *
 * <p>复用存量 {@link LlmWikiClient.search}（只注入不改动）；space 解析优先级=
 * 请求显式 spaceName → RetrievalSpaceResolver(ds_id→ds_config.space_name) → A 库语义命中条目 space_name。
 * llm-wiki 不可达/超时按 BR-003 降级：跳过 B 路并返回可读 degradation，不抛错不阻塞 A 路。</p>
 */
@Slf4j
@Component
public class LlmWikiBSearcher {

    private static final int TRUNCATE_LENGTH = 500;

    private final LlmWikiClient llmWikiClient;
    private final RetrievalSpaceResolver spaceResolver;
    private final RetrievalProperties properties;

    public LlmWikiBSearcher(LlmWikiClient llmWikiClient,
                            RetrievalSpaceResolver spaceResolver,
                            RetrievalProperties properties) {
        this.llmWikiClient = llmWikiClient;
        this.spaceResolver = spaceResolver;
        this.properties = properties;
    }

    /**
     * B 路召回统一入口。
     *
     * @param spaceName         显式 spaceName（可为 null）
     * @param dsId              ds_id（用于注册表映射）
     * @param kbASemanticSpace  A 库语义命中携带的空间名（可为 null）
     * @param query             检索文本（问题/关键词）
     * @param topK              通道条数
     * @param degradeHint       降级提示收集器（BR-003 可读提示，可为 null）
     * @return 命中的统一条目（B_WIKI_DOC）；B 不可用返回空列表并写入降级提示
     */
    public List<RetrievalHit> search(String spaceName, String dsId, String kbASemanticSpace,
                                     String query, int topK, List<String> degradeHint) {
        String space = resolveSpace(spaceName, dsId, kbASemanticSpace);
        if (space == null || space.isBlank()) {
            if (degradeHint != null) {
                degradeHint.add("llm-wiki 空间名不可解析，已跳过知识库 B 召回");
            }
            return Collections.emptyList();
        }
        try {
            SearchRequest req = new SearchRequest(query == null ? "" : query, topK, null);
            List<SearchHitVO> hits = llmWikiClient.search(space, req);
            if (hits == null || hits.isEmpty()) {
                return Collections.emptyList();
            }
            List<RetrievalHit> out = new ArrayList<>(hits.size());
            for (SearchHitVO h : hits) {
                out.add(toHit(space, h));
            }
            return out;
        } catch (LlmWikiClientException e) {
            log.warn("retrieval: llm-wiki 不可用，B 路降级(仅 A 库): {}", e.getMessage());
            if (degradeHint != null) {
                degradeHint.add("llm-wiki 不可用，已跳过知识库 B 召回");
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("retrieval: llm-wiki 检索异常，B 路降级(仅 A 库): {}", e.getMessage());
            if (degradeHint != null) {
                degradeHint.add("llm-wiki 不可用，已跳过知识库 B 召回");
            }
            return Collections.emptyList();
        }
    }

    /** 空间名解析：显式 spaceName → ds 注册表 space_name → A 库语义命中 space_name */
    private String resolveSpace(String spaceName, String dsId, String kbASemanticSpace) {
        if (spaceName != null && !spaceName.isBlank()) {
            return spaceName.trim();
        }
        String fromDs = spaceResolver.resolveSpaceName(dsId);
        if (fromDs != null && !fromDs.isBlank()) {
            return fromDs;
        }
        return kbASemanticSpace;
    }

    private RetrievalHit toHit(String space, SearchHitVO h) {
        RetrievalHit hit = new RetrievalHit();
        hit.setSource(RetrievalSourceType.B_WIKI_DOC);
        hit.setChannel(RetrievalChannel.WIKI_DOCS.getValue());
        // B 库页面以 path 作为知识 id
        hit.setKbId(h.path());
        hit.setTitle(h.title() == null ? h.path() : h.title());
        hit.setContent(truncate(h.content(), TRUNCATE_LENGTH));
        hit.setConfidence(normalizeScore(h.score()));
        // B 库为人工维护页面，按人工口径视为 verified=1
        hit.setVerified(1);
        hit.setConflictFlag(ConflictFlag.NONE.getValue());
        hit.setSpaceName(space);
        hit.setEvidence(List.of(EvidenceRef.of(RetrievalSourceType.B_WIKI_DOC.getValue(),
                h.path(), h.path(), truncate(h.title(), 120))));
        hit.setSemanticFingerprint(normalize(h.title() == null ? h.path() : h.title()));
        return hit;
    }

    /** score → confidence 归一化（0~1）；score 为 null 默认 0.6 */
    static BigDecimal normalizeScore(Double score) {
        if (score == null) {
            return new BigDecimal("0.600");
        }
        double v = score;
        if (v > 1) {
            // 部分服务返回 0~100 或未归一化相似度，除以 100 夹取
            v = v / 100.0;
        }
        if (v < 0) {
            v = 0;
        }
        if (v > 1) {
            v = 1;
        }
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int len) {
        if (s == null) {
            return null;
        }
        return s.length() <= len ? s : s.substring(0, len);
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase().replaceAll("\\s+", "");
    }
}
