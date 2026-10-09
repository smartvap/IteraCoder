package com.agenthub.ai.rcagent.retrieval.rerank;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.retrieval.config.RetrievalProperties;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalErrorCode;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalRequest;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import com.agenthub.ai.rcagent.retrieval.support.FusionMerger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Bge Rerank 精排（P2，配置驱动 {@code agenthub.retrieval.rerank.bge-enabled=true}）。
 *
 * <p>JDK HttpClient 调用 bge-url（HTTP/1.1 风格与 LlmWikiClient 一致），
 * 请求 {question, hits:[{title, content}]}，响应 {scores:[...]}；按 score 降序重排。
 * 服务不可达/调用失败抛 42104（显式启用 Rerank 时的明确错误，NFR-BE-003）。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agenthub.retrieval.rerank", name = "bge-enabled", havingValue = "true")
public class BgeReranker implements RetrievalReranker {

    private final RetrievalProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public BgeReranker(RetrievalProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(properties.getRerank().getTimeout() == null ? 3000L : properties.getRerank().getTimeout()))
                .build();
    }

    @Override
    public List<RetrievalHit> rerank(RetrievalRequest request, List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new ArrayList<>();
        }
        String url = properties.getRerank().getBgeUrl();
        if (url == null || url.isBlank()) {
            throw new BusinessException(RetrievalErrorCode.RERANK_UNAVAILABLE, "Rerank 服务不可用: bge-url 未配置");
        }
        try {
            // 构造轻量请求体：只传标题/正文短截断，避免 token/内容全量外泄
            List<java.util.Map<String, String>> payloadHits = new ArrayList<>();
            for (RetrievalHit h : hits) {
                payloadHits.add(java.util.Map.of(
                        "title", h.getTitle() == null ? "" : h.getTitle(),
                        "content", h.getContent() == null ? "" : h.getContent()));
            }
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "question", request.getQuestion() == null ? "" : request.getQuestion(),
                    "hits", payloadHits));
            HttpRequest httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getRerank().getTimeout() == null ? 3000L : properties.getRerank().getTimeout()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new BusinessException(RetrievalErrorCode.RERANK_UNAVAILABLE,
                        "Rerank 服务不可用: HTTP " + resp.statusCode());
            }
            return reorderByScores(resp.body(), hits);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("retrieval: bge-reranker 调用失败: {}", e.getMessage());
            throw new BusinessException(RetrievalErrorCode.RERANK_UNAVAILABLE, "Rerank 服务不可用");
        }
    }

    /** 解析 {scores:[...]} 并按 score 降序重排 */
    private List<RetrievalHit> reorderByScores(String respBody, List<RetrievalHit> hits) throws Exception {
        JsonNode root = objectMapper.readTree(respBody);
        JsonNode scoresNode = root.get("scores");
        if (scoresNode == null || !scoresNode.isArray() || scoresNode.size() < hits.size()) {
            log.warn("retrieval: bge-reranker 响应缺少完整 scores，保持原顺序");
            return new ArrayList<>(hits);
        }
        List<RetrievalHit> out = new ArrayList<>(hits);
        out.sort((a, b) -> {
            int ia = hits.indexOf(a);
            int ib = hits.indexOf(b);
            double sa = scoresNode.get(ia).asDouble(0);
            double sb = scoresNode.get(ib).asDouble(0);
            return Double.compare(sb, sa);
        });
        // 精排后仍保留去重键（FusionMerger 已设置）
        for (RetrievalHit h : out) {
            if (h.getSemanticFingerprint() == null) {
                h.setSemanticFingerprint(FusionMerger.normalizeFingerprint(h.getTitle()));
            }
        }
        return out;
    }
}
