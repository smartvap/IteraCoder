package com.agenthub.ai.rcagent.retrieval.model;

import com.agenthub.ai.rcagent.retrieval.constant.RetrievalSourceType;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一命中条目（RetrievalHit，api-contract data.items[].source/kb_id/title/...）。
 *
 * <p>所有召回通道（A 五路 + B search）统一映射为该结构：
 * source 来源标注、kb_id（A 库 kb 对象 id / B 库 llm-wiki path）、evidence 证据引用可溯源（BR-004）。
 * 内部附带 {@code dedupKey}/{@code sourcePriority} 辅助字段供 FusionMerger 使用（@JsonIgnore 不输出）。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalHit {

    /** 来源标注（A_SCHEMA_TABLE/.../B_WIKI_DOC） */
    private RetrievalSourceType source;

    /** 知识 id（A 库 kb 对象 id；B 库为 llm-wiki 页面 path） */
    private String kbId;

    /** 标题（表名/指标名/概念名/页面标题） */
    private String title;

    /** 知识正文（截断长度内） */
    private String content;

    /** 置信度（0~1） */
    private BigDecimal confidence;

    /** 是否已验证（0/1） */
    private Integer verified;

    /** 证据引用列表（BR-004 可溯源） */
    private List<EvidenceRef> evidence = new ArrayList<>();

    /** NONE/CONFLICT（冲突知识保留全部） */
    private String conflictFlag;

    /** A 库对象行 id（用于 kb_id 拼接与去重） */
    private Long rowId;

    /** 知识库 B 空间名（semantic/metric/concept 命中可携带） */
    private String spaceName;

    /** 内容归属通道键（schema_tables/.../wiki_docs） */
    private String channel;

    /** FusionMerger 去重辅助键（不序列化输出） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String dedupKey;

    /** 来源类型排序优先级（越小越靠前，FusionMerger 使用，不序列化输出） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private int sourcePriority;

    /** 语义指纹（标题/指纹归一，不序列化输出） */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private String semanticFingerprint;

    public String sourceValue() {
        return source == null ? null : source.getValue();
    }

    /** confidence 空安全读取（默认 0） */
    public java.math.BigDecimal confidenceOrZero() {
        return confidence == null ? java.math.BigDecimal.ZERO : confidence;
    }

    /** verified 空安全读取（默认 0） */
    public int verifiedOrZero() {
        return verified == null ? 0 : verified;
    }
}
