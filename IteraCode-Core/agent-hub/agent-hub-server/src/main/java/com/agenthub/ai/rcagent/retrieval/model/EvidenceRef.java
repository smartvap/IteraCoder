package com.agenthub.ai.rcagent.retrieval.model;

import lombok.Data;

/**
 * 证据引用（EvidenceRef，BR-004：上下文每条知识必须携带 source/kbId/path/excerpt 引用）。
 */
@Data
public class EvidenceRef {

    /** 来源标注 */
    private String source;

    /** 知识 id（A 库 kb 对象 id；B 库页面 path） */
    private String kbId;

    /** 路径（table/xx、concept/xx、llm-wiki 页面 path） */
    private String path;

    /** 证据摘录（截断） */
    private String excerpt;

    public static EvidenceRef of(String source, String kbId, String path, String excerpt) {
        EvidenceRef ref = new EvidenceRef();
        ref.setSource(source);
        ref.setKbId(kbId);
        ref.setPath(path);
        ref.setExcerpt(excerpt);
        return ref;
    }
}
