package com.agenthub.ai.rcagent.retrieval.support;

import com.agenthub.ai.rcagent.retrieval.config.RetrievalProperties;
import com.agenthub.ai.rcagent.retrieval.constant.ConflictFlag;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalSourceType;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 融合去重 + 冲突标记 + 排序（FusionMerger）。
 *
 * <p>合并 A/B 多路结果，按 (ds_id, 目标对象类型+标识, 语义指纹) 去重：
 * 相同目标对象的多条高置信冲突知识全部保留并标记 conflict_flag=CONFLICT（由 Rerank/上层裁决，不静默丢弃，FR-004）；
 * 排序键：verified 优先 → confidence 降序 → 来源类型优先级。</p>
 */
@Slf4j
@Component
public class FusionMerger {

    /** 来源类型优先级：A 库结构化口径优先于 B 库 FAQ；同类按定义/模板优先 */
    private static final Map<RetrievalSourceType, Integer> SOURCE_PRIORITY = new LinkedHashMap<>();

    static {
        SOURCE_PRIORITY.put(RetrievalSourceType.A_CONCEPT, 1);
        SOURCE_PRIORITY.put(RetrievalSourceType.A_METRIC, 2);
        SOURCE_PRIORITY.put(RetrievalSourceType.A_SCHEMA_TABLE, 3);
        SOURCE_PRIORITY.put(RetrievalSourceType.A_SCHEMA_COLUMN, 4);
        SOURCE_PRIORITY.put(RetrievalSourceType.A_TEMPLATE, 5);
        SOURCE_PRIORITY.put(RetrievalSourceType.A_RELATION, 6);
        SOURCE_PRIORITY.put(RetrievalSourceType.A_BIZ_LINK, 7);
        SOURCE_PRIORITY.put(RetrievalSourceType.B_WIKI_DOC, 8);
        // 索引来源排在全部既有来源之后（不动既有 1~8，避免排序回归）
        SOURCE_PRIORITY.put(RetrievalSourceType.A_SCHEMA_INDEX, 9);
    }

    private final RetrievalProperties properties;

    public FusionMerger(RetrievalProperties properties) {
        this.properties = properties;
    }

    /**
     * 合并去重排序。
     *
     * @param dsId 数据源实例 ID（BR-001 隔离维度，仅参与去重上下文）
     * @param hits 各通道召回的统一命中（A+B）
     * @param topK 保留上限
     */
    public List<RetrievalHit> merge(String dsId, List<RetrievalHit> hits, int topK) {
        if (hits == null || hits.isEmpty()) {
            return new ArrayList<>();
        }
        // 第一步：按去重键分组（目标对象类型+标识+语义指纹），组内同对象冲突知识全保留并标记 CONFLICT
        Map<String, List<RetrievalHit>> groups = new LinkedHashMap<>();
        for (RetrievalHit hit : hits) {
            if (hit == null) {
                continue;
            }
            fillDedupMeta(hit);
            String key = dedupKey(dsId, hit);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(hit);
        }

        List<RetrievalHit> merged = new ArrayList<>();
        for (List<RetrievalHit> group : groups.values()) {
            // 组内按 (verified, confidence) 取最优；若组内存在不同知识对象(行/来源)则视为冲突保留全部
            RetrievalHit best = group.stream()
                    .max(Comparator.comparing(RetrievalHit::verifiedOrZero)
                            .thenComparing(RetrievalHit::confidenceOrZero)
                            .thenComparing(RetrievalHit::getSourcePriority, Comparator.reverseOrder()))
                    .orElse(null);
            if (best == null) {
                continue;
            }
            boolean hasConflict = group.stream().anyMatch(h -> {
                String bestIdentity = best.getRowId() == null ? best.getKbId() : String.valueOf(best.getRowId());
                String hitIdentity = h.getRowId() == null ? h.getKbId() : String.valueOf(h.getRowId());
                return !java.util.Objects.equals(bestIdentity, hitIdentity);
            });
            if (hasConflict) {
                // 冲突知识保留全部 + 标记，交上层/Rerank 裁决（FR-004）
                for (RetrievalHit hit : group) {
                    hit.setConflictFlag(ConflictFlag.CONFLICT.getValue());
                    merged.add(hit);
                }
            } else {
                best.setConflictFlag(ConflictFlag.NONE.getValue());
                merged.add(best);
            }
        }

        // 第二步：排序 verified → confidence → 来源类型优先级
        merged.sort(Comparator
                .comparing(RetrievalHit::verifiedOrZero).reversed()
                .thenComparing(RetrievalHit::confidenceOrZero, Comparator.reverseOrder())
                .thenComparingInt(RetrievalHit::getSourcePriority));

        // top_k 截断
        int limit = Math.min(topK <= 0 ? properties.topKValue() : topK, merged.size());
        return new ArrayList<>(merged.subList(0, limit));
    }

    /** 设置去重/排序辅助元数据 */
    private void fillDedupMeta(RetrievalHit hit) {
        if (hit.getSource() == null) {
            hit.setSource(RetrievalSourceType.B_WIKI_DOC);
        }
        hit.setSourcePriority(SOURCE_PRIORITY.getOrDefault(hit.getSource(), 99));
        if (hit.getSemanticFingerprint() == null) {
            hit.setSemanticFingerprint(normalizeFingerprint(hit.getTitle()));
        }
        hit.setDedupKey(targetIdentity(hit));
    }

    /** 去重键：ds_id|目标对象类型+标识|语义指纹 */
    private String dedupKey(String dsId, RetrievalHit hit) {
        return dsId + "|" + targetIdentity(hit) + "|" + (hit.getSemanticFingerprint() == null ? "" : hit.getSemanticFingerprint());
    }

    /** 目标对象类型+标识：表/字段/模板/链路/关系/指标/概念/B path */
    private String targetIdentity(RetrievalHit hit) {
        RetrievalSourceType source = hit.getSource();
        String kb = hit.getKbId() == null ? "" : hit.getKbId();
        String title = normalizeFingerprint(hit.getTitle());
        if (source == null) {
            return "WIKI:" + kb;
        }
        return switch (source) {
            case A_SCHEMA_TABLE -> "TABLE:" + title;
            case A_SCHEMA_COLUMN -> "COLUMN:" + title;
            case A_TEMPLATE -> "TEMPLATE:" + kb;
            case A_BIZ_LINK -> "BIZ_LINK:" + kb;
            case A_RELATION -> "RELATION:" + title;
            case A_METRIC -> "METRIC:" + title;
            case A_CONCEPT -> "CONCEPT:" + title;
            case B_WIKI_DOC -> "WIKI:" + kb;
            case A_SCHEMA_INDEX -> "INDEX:" + title;
        };
    }

    /** 语义指纹：标题归一（小写去空白） */
    public static String normalizeFingerprint(String title) {
        if (title == null) {
            return "";
        }
        return title.trim().toLowerCase().replaceAll("\\s+", " ");
    }
}
