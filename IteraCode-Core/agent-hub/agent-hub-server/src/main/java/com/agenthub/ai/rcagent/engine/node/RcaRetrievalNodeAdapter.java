package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataDictionaryLookup;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataDictionaryLookup.LookupResult;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataEvidenceRenderer;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataQuestionDetector;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RCAgent 检索节点适配器（rca1.5.0.0 metadata-evidence-lookup）。
 *
 * <p>职责：包装存量 {@link RcaRetrievalNode}（存量代码零改动），在委托其完成通用检索后，
 * 仅对**元数据/结构类问题**追加一段 {@code [元数据字典直查]} 维度证据到既有 {@code retrieval_context}，
 * 使 {@link RcaAnswerNode} 能拿到索引/主键/外键等被通用 {@code topK} 融合截断挤出的证据。</p>
 *
 * <p>分支契约：</p>
 * <ul>
 *   <li>B0 直查组件/判定器缺失（未注入或配置异常）⇒ 直接委托，行为等价改造前；</li>
 *   <li>B1 判定抛异常 ⇒ 记录告警后返回基线（降级不阻断）；</li>
 *   <li>B2 非元数据问题（含全部 {@code QUERY}）⇒ 返回基线，<b>不查询、不追加、不覆写、不额外发布</b>（零回归）；</li>
 *   <li>B3 元数据命中但 {@code ds_id} 空白 / 表名未解析 / 直查异常 ⇒ 返回基线（不产出证据）；</li>
 *   <li>B4 元数据命中且证据段非空 ⇒ 合并（优先保证据段）+ 覆写发布 + 返回新 Map（其余键原样保留）。</li>
 * </ul>
 *
 * <p>约束：维度唯一来源为 {@link RcaMetadataQuestionDetector}（不解析 {@code intent_result} 文本、
 * 不新增 State 键）；日志仅输出维度与计数，禁止输出问题全文与凭据类信息（AGENTS.md 4.3/5.4）。</p>
 */
@Slf4j
public class RcaRetrievalNodeAdapter implements NodeAction {

    /** 检索上下文合并上限（与 RcaRetrievalNode 的 6000 口径一致） */
    private static final int CONTEXT_MAX = 6000;
    /** 基线与证据段之间的分隔 */
    private static final String SECTION_SEPARATOR = "\n\n";
    /** RcaNodeUtils.truncate 超限追加的后缀：预算内需预留其长度，保证合并结果不超上限 */
    private static final String TRUNCATE_SUFFIX = "...[已截断]";
    /** 既有工作流运行态标签 */
    private static final String STATUS_RUNNING = "RUNNING";
    /** 降级告警中异常摘要保留长度 */
    private static final int WARN_ERROR_MAX = 200;

    private final WorkflowEventBus eventBus;
    private final RcaRetrievalNode delegate;
    private final RcaMetadataQuestionDetector detector;
    private final RcaMetadataDictionaryLookup lookup;

    /**
     * @param eventBus SSE 事件发布（可为 null，发布前判空）
     * @param delegate 存量检索节点（不修改其代码）
     * @param detector 元数据问题判定器（可传 {@link RcaMetadataQuestionDetector#INSTANCE}；null ⇒ 降级）
     * @param lookup   元数据字典直查组件（null ⇒ 降级为纯委托）
     */
    public RcaRetrievalNodeAdapter(WorkflowEventBus eventBus,
                                   RcaRetrievalNode delegate,
                                   RcaMetadataQuestionDetector detector,
                                   RcaMetadataDictionaryLookup lookup) {
        this.eventBus = eventBus;
        this.delegate = delegate;
        this.detector = detector;
        this.lookup = lookup;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        // B0：直查组件缺失（未注入/配置异常）⇒ 逐键委托存量节点，行为等价改造前
        if (lookup == null || detector == null) {
            return delegate.apply(state);
        }
        Map<String, Object> base = delegate.apply(state);

        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String dsId = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.DS_ID, "");
        RcaMetadataQuestionDetector.Detection detection;
        try {
            detection = detector.detect(question);
        } catch (Exception e) {
            // B1：判定失败降级不阻断（不产出证据）；异常摘要截断，禁止拼接凭据
            log.warn("RCAgent 元数据判定异常，跳过字典直查: error={}",
                    RcaNodeUtils.truncate(String.valueOf(e.getMessage()), WARN_ERROR_MAX));
            return base;
        }
        // B2：非元数据问题零回归（不查询、不追加、不覆写、不额外发布）
        if (detection == null || !detection.metadata() || detection.category() == null) {
            return base;
        }
        // B3：ds_id 空白 ⇒ 不调用字典服务（G-06）
        if (dsId == null || dsId.isBlank()) {
            return base;
        }

        LookupResult lookupResult;
        String section;
        try {
            // B3/B4：直查 + 渲染，任一步骤异常均降级为基线上下文（G-13）
            lookupResult = lookup.lookup(dsId, question, detection.category());
            section = RcaMetadataEvidenceRenderer.render(lookupResult);
        } catch (Exception e) {
            log.warn("RCAgent 元数据字典直查异常，降级为基线上下文: category={}, error={}",
                    detection.category(), RcaNodeUtils.truncate(String.valueOf(e.getMessage()), WARN_ERROR_MAX));
            return base;
        }
        // B4 前置：段为空（表未解析/无证据）⇒ 逐键返回基线
        if (section == null || section.isEmpty()) {
            return base;
        }
        return mergeAndPublish(state, base, lookupResult, section, detection.category());
    }

    /**
     * B4：合并证据段（先压缩基线再拼接，保证证据段完整且总长 ≤ 6000）、覆写发布并返回新 Map。
     *
     * <p>合并时**优先保留证据段**：证据段才是本维度的答案依据，必要时压缩基线上下文。</p>
     */
    private Map<String, Object> mergeAndPublish(OverAllState state, Map<String, Object> base,
                                                LookupResult lookupResult, String section,
                                                RcaMetadataQuestionDetector.MetadataCategory category) {
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        Object baseContextValue = base.get(RcaWorkflowKeys.RETRIEVAL_CONTEXT);
        String baseContext = baseContextValue instanceof String text ? text : "";

        // 预算预留分隔符与截断后缀，确保截断后拼接结果仍不超上限
        int remain = Math.max(0, CONTEXT_MAX - section.length() - SECTION_SEPARATOR.length());
        String combined = truncateForBudget(baseContext, remain) + SECTION_SEPARATOR + section;

        publish(threadId, RcaWorkflowKeys.RETRIEVAL_CONTEXT, combined, STATUS_RUNNING);
        int hitCount = lookupResult == null || lookupResult.rows() == null ? 0 : lookupResult.rows().size();
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                "元数据字典直查完成：命中 " + hitCount + " 条（维度 " + category + "）", STATUS_RUNNING);

        List<String> tables = lookupResult == null || lookupResult.resolvedTables() == null
                ? List.of() : lookupResult.resolvedTables();
        log.info("RCAgent 元数据字典直查: category={}, tableSource={}, tableCount={}, hits={}, sectionLength={}",
                category, lookupResult == null ? null : lookupResult.tableSource(),
                tables.size(), hitCount, section.length());

        // 其余 State 键原样保留（仅覆写检索上下文）
        Map<String, Object> result = new HashMap<>(base);
        result.put(RcaWorkflowKeys.RETRIEVAL_CONTEXT, combined);
        return result;
    }

    /**
     * 把基线上下文压缩到给定预算内。
     *
     * <p>{@link RcaNodeUtils#truncate} 超限时会追加 {@code "...[已截断]"} 后缀，
     * 故预算内需预留后缀长度，避免「截断后拼接」突破 6000 上限（G-08）。</p>
     */
    private static String truncateForBudget(String context, int budget) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        if (context.length() <= budget) {
            return context;
        }
        return RcaNodeUtils.truncate(context, Math.max(0, budget - TRUNCATE_SUFFIX.length()));
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
