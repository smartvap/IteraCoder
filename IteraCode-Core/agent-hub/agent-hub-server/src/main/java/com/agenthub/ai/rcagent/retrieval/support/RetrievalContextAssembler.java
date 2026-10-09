package com.agenthub.ai.rcagent.retrieval.support;

import com.agenthub.ai.rcagent.retrieval.config.RetrievalProperties;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalSourceType;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalContextVO;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一上下文组装（RetrievalContextAssembler，FR-BE-005）。
 *
 * <p>schema/template/semantic/faq 分组 + evidence 引用（条目已携带）+ 按 context_max_chars 裁剪防 token 膨胀；
 * context.items 与 data.items 等价（已排序去重后的全量条目）。</p>
 */
@Component
public class RetrievalContextAssembler {

    private final RetrievalProperties properties;

    public RetrievalContextAssembler(RetrievalProperties properties) {
        this.properties = properties;
    }

    /**
     * 组装统一上下文（分组 + 字符预算裁剪）。
     *
     * @param items 已去重排序（FusionMerger 后，可选 Rerank 后）的统一命中
     * @return 分组上下文；items 为裁剪后全量
     */
    public RetrievalContextVO assemble(List<RetrievalHit> items) {
        RetrievalContextVO context = new RetrievalContextVO();
        if (items == null || items.isEmpty()) {
            context.setItems(new ArrayList<>());
            return context;
        }
        List<RetrievalHit> schema = new ArrayList<>();
        List<RetrievalHit> template = new ArrayList<>();
        List<RetrievalHit> semantic = new ArrayList<>();
        List<RetrievalHit> faq = new ArrayList<>();
        for (RetrievalHit hit : items) {
            RetrievalSourceType source = hit.getSource();
            if (source == null) {
                continue;
            }
            switch (source) {
                case A_SCHEMA_TABLE, A_SCHEMA_COLUMN, A_RELATION, A_BIZ_LINK -> schema.add(hit);
                case A_TEMPLATE -> template.add(hit);
                case A_METRIC, A_CONCEPT -> semantic.add(hit);
                case B_WIKI_DOC -> faq.add(hit);
                // 索引命中归入 schema 分组（与表/列/关系/链路同组）
                case A_SCHEMA_INDEX -> schema.add(hit);
            }
        }
        context.setSchemaItems(clip(schema));
        context.setTemplateItems(clip(template));
        context.setSemanticItems(clip(semantic));
        context.setFaqItems(clip(faq));
        context.setItems(clip(items));
        return context;
    }

    /**
     * 按 context_max_chars 字符预算裁剪条目 content（保持顺序；超预算条目正文置空标注省略）。
     */
    public List<RetrievalHit> clip(List<RetrievalHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return new ArrayList<>();
        }
        int budget = properties.contextMaxCharsValue();
        int used = 0;
        List<RetrievalHit> out = new ArrayList<>();
        for (RetrievalHit hit : hits) {
            String content = hit.getContent() == null ? "" : hit.getContent();
            if (used >= budget) {
                RetrievalHit slim = copySlim(hit);
                out.add(slim);
                continue;
            }
            int remaining = budget - used;
            if (content.length() > remaining) {
                RetrievalHit slim = copySlim(hit);
                slim.setContent(content.substring(0, remaining));
                out.add(slim);
                used = budget;
            } else {
                out.add(hit);
                used += content.length();
            }
        }
        return out;
    }

    private RetrievalHit copySlim(RetrievalHit hit) {
        RetrievalHit slim = new RetrievalHit();
        slim.setSource(hit.getSource());
        slim.setKbId(hit.getKbId());
        slim.setRowId(hit.getRowId());
        slim.setTitle(hit.getTitle());
        slim.setContent(null);
        slim.setConfidence(hit.getConfidence());
        slim.setVerified(hit.getVerified());
        slim.setEvidence(hit.getEvidence());
        slim.setConflictFlag(hit.getConflictFlag());
        slim.setSpaceName(hit.getSpaceName());
        slim.setChannel(hit.getChannel());
        slim.setDedupKey(hit.getDedupKey());
        slim.setSourcePriority(hit.getSourcePriority());
        slim.setSemanticFingerprint(hit.getSemanticFingerprint());
        return slim;
    }
}
