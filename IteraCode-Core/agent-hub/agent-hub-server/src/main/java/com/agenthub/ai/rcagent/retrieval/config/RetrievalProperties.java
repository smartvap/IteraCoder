package com.agenthub.ai.rcagent.retrieval.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 双重知识库融合检索配置（{@code agenthub.retrieval.*}）。
 *
 * <p>对应 api-contract：top_k / per_channel_top_k / context_max_chars / cold_start_allowed /
 * rerank.bge-enabled / rerank.bge-url / rerank.timeout 等；默认值 P1 规则降级、bge 关闭。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agenthub.retrieval")
public class RetrievalProperties {

    /** 合并去重后最终保留条目上限（top_k，HTTP 请求可覆盖） */
    private Integer topK = 20;

    /** 每路召回通道上限（per_channel_top_k） */
    private Integer perChannelTopK = 10;

    /** 统一上下文最大字符预算（防 token 膨胀，按字符裁剪） */
    private Integer contextMaxChars = 8000;

    /** 请求默认是否允许冷生成降级（FR-006 开关，HTTP 请求可覆盖） */
    private Boolean coldStartAllowed = false;

    /** 高置信阈值：verified=1 且 confidence 不低于该值的命中才算高置信（冷启动检测用） */
    private Double highConfidenceMin = 0.6;

    /**
     * 合并结果最小置信度（{@code agenthub.retrieval.min-confidence}）：融合合并后低于该值的命中会被过滤，
     * 用于去除与问题无关的低分噪声文档（如 llm-wiki B 路 0.02 级别的无关命中）。默认 0.1；
     * null 或 &lt;=0 表示不过滤（保持历史行为）。
     *
     * <p>注意与 {@link #highConfidenceMin} 区分：后者是「高置信」判定阈值，仅供冷启动检测使用；
     * 本字段是结果过滤阈值，由检索节点组装为 {@code filters.confidence_min} 作用于最终结果集。</p>
     */
    private Double minConfidence = 0.1;

    /** llm-wiki 调用最长等待毫秒（超过视为不可用降级） */
    private Long kbBTimeoutMs = 3000L;

    /** Rerank（P2）配置 */
    private Rerank rerank = new Rerank();

    @Data
    public static class Rerank {

        /** 是否启用 bge-reranker（P2，默认 false → P1 规则降级） */
        private Boolean bgeEnabled = false;

        /** bge-reranker 服务地址（如 http://localhost:8001/rerank） */
        private String bgeUrl = "";

        /** bge 调用超时（毫秒） */
        private Long timeout = 3000L;
    }

    // ============ 便捷读取（避免调用处重复判空） ============

    public int topKValue() {
        return topK == null || topK < 1 ? 20 : Math.min(topK, 100);
    }

    public int perChannelTopKValue() {
        return perChannelTopK == null || perChannelTopK < 1 ? 10 : Math.min(perChannelTopK, 50);
    }

    public int contextMaxCharsValue() {
        return contextMaxChars == null || contextMaxChars < 1 ? 8000 : contextMaxChars;
    }

    public boolean coldStartAllowedValue() {
        return Boolean.TRUE.equals(coldStartAllowed);
    }

    public double highConfidenceMinValue() {
        return highConfidenceMin == null ? 0.6 : highConfidenceMin;
    }

    /**
     * 合并结果最小置信度过滤值：null 或 &lt;=0 表示不过滤（返回 null），否则返回对应 BigDecimal。
     * 供检索节点直接写入 {@code filters.confidence_min}，避免调用处重复判空。
     */
    public java.math.BigDecimal minConfidenceValue() {
        if (minConfidence == null || minConfidence <= 0) {
            return null;
        }
        return java.math.BigDecimal.valueOf(minConfidence);
    }

    public long kbBTimeoutMsValue() {
        return kbBTimeoutMs == null ? 3000L : kbBTimeoutMs;
    }

    public boolean bgeEnabledValue() {
        return rerank != null && Boolean.TRUE.equals(rerank.getBgeEnabled());
    }
}
