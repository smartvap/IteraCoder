package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.retrieval.config.RetrievalProperties;
import com.agenthub.ai.rcagent.retrieval.dto.QueryElements;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalRequest;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalResult;
import com.agenthub.ai.rcagent.retrieval.service.RetrievalService;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RCAgent 检索编排节点：调用 module-005 dual-retrieval（RetrievalService.search）输出统一上下文。
 * <p>
 * P0 占位契约：检索服务可用时真实调用；服务缺失/调用失败时降级写入上下文说明，不阻断主链路。</p>
 */
@Slf4j
public class RcaRetrievalNode implements NodeAction {

    /**
     * 标识符形态 token（表名/字段名/英文实体）：首字符为字母或下划线，后接字母/数字/下划线。
     * 用于从中文问题里提取 A 路（schema/模板）可命中的 LIKE 关键词，
     * 同时把「问题点名的列名」传给下游做按需列展开（RetrievalAdapter）。
     */
    private static final Pattern IDENTIFIER_TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 标识符 token 数量上限：避免 keywords 膨胀影响下游主 token 选择与日志体积 */
    private static final int IDENTIFIER_TOKEN_LIMIT = 10;

    /** 中文（CJK）连续串：用于抽取语义词（供冷启动判定/覆盖度，不用于 A 路 LIKE） */
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    /**
     * 中文语义词提取的停用词（按长度降序匹配，避免短词先命中切断长词）。
     *
     * <p>覆盖疑问词（多少/几）、统计动作词（统计/查询/数量/条数/记录/数据）、
     * 结构词与连接词（表/的/是/有/无/按/和/与/个/条/为），剔除后剩余的 2~6 字片段
     * 才是可用于覆盖度比对的语义候选（如「状态」「完成」）。</p>
     */
    private static final List<String> CJK_STOPWORDS = List.of(
            "多少", "数据", "条数", "记录", "数量", "统计", "查询",
            "表", "的", "是", "有", "无", "按", "和", "与", "几", "个", "条", "为");

    /** 中文语义词长度区间（过短无区分度，过长多为整句黏连） */
    private static final int CJK_WORD_MIN_LEN = 2;
    private static final int CJK_WORD_MAX_LEN = 6;

    /** 语义词数量上限，避免 entities 膨胀影响概念回填与日志体积 */
    private static final int CHINESE_ENTITY_LIMIT = 10;

    private final WorkflowEventBus eventBus;
    private final RetrievalService retrievalService;
    private final RetrievalProperties properties;

    public RcaRetrievalNode(WorkflowEventBus eventBus, RetrievalService retrievalService,
                            RetrievalProperties properties) {
        this.eventBus = eventBus;
        this.retrievalService = retrievalService;
        this.properties = properties;
    }

    /**
     * 组装检索请求（包级可见，便于单测；不调 LLM、不依赖 state）。
     *
     * <p>降噪与 A 路恢复要点：</p>
     * <ul>
     *   <li>query_elements：从问题中提取**全部**标识符 token 作为 keywords（去重保序、上限 10），
     *       避免下游 {@code buildKeyword} 退化用整句问题做 {@code LIKE '%整句%'}（A 路 schema 通道无法命中）；
     *       keywords 同时把「问题点名的列名」传给 RetrievalAdapter 做按需列展开；无 token 时不设置要素，
     *       保持旧行为（回退整句）。下游 {@code RetrievalService.buildKeyword} 会从中选最长单 token 作为
     *       A 路 schema LIKE 关键词，保持单子串语义。</li>
     *   <li>query_elements.entities：额外抽取中文语义词（CJK 串去停用词后的 2~6 字片段），
     *       供 {@code ColdStartDetector} 判定与覆盖度（matched/missing）比对；不塞进 keyword——
     *       A 路 keyword 必须是单 token LIKE 子串，列级召回改由 P1「命中表列展开」覆盖。</li>
     *   <li>filters.confidence_min：写入配置的最小置信度，过滤 llm-wiki B 路无关低分文档；
     *       includeDraft 保持为空/false，不豁免过滤。</li>
     * </ul>
     */
    RetrievalRequest buildRequest(String question, String dsId, String threadId, String intentType) {
        RetrievalRequest request = new RetrievalRequest();
        request.setDsId(dsId);
        request.setQuestion(question);
        request.setThreadId(threadId);
        // CHAT 意图放宽检索：允许冷启动降级，让知识问答也能拿到弱可用上下文
        request.setColdStartAllowed("CHAT".equals(intentType));
        request.setTopK(10);
        request.setPerChannelTopK(5);

        // A 路关键词恢复：提取问题中全部标识符 token（下游 buildKeyword 从中选最长单 token 做 LIKE；
        // 其余 token 作为「问题点名的列名」传给列展开做按需召回）
        List<String> keywords = extractIdentifierTokens(question);
        // 中文语义词：供冷启动/覆盖度使用（与 keywords 分列，避免污染 A 路多 token LIKE）
        List<String> chineseEntities = extractChineseEntities(question);
        if (!keywords.isEmpty() || !chineseEntities.isEmpty()) {
            QueryElements elements = new QueryElements();
            if (!keywords.isEmpty()) {
                elements.setKeywords(keywords);
            }
            if (!chineseEntities.isEmpty()) {
                elements.setEntities(chineseEntities);
            }
            request.setQueryElements(elements);
        }

        // 低分噪声过滤：仅在配置了有效阈值（>0）时启用，阈值 <=0/null 表示不过滤
        if (properties != null && properties.minConfidenceValue() != null) {
            RetrievalRequest.Filters filters = new RetrievalRequest.Filters();
            filters.setConfidenceMin(properties.minConfidenceValue());
            request.setFilters(filters);
        }
        return request;
    }

    /**
     * 提取问题中全部标识符 token（去重保序、上限 {@link #IDENTIFIER_TOKEN_LIMIT} 个）。
     *
     * <p>用途：① 下游 {@code RetrievalService.buildKeyword} 从中选最长单 token 作为 A 路 schema
     * LIKE 关键词；② 其余 token 作为「问题点名的列名」传给 {@code RetrievalAdapter} 做按需列展开。
     * 无可识别 token（如纯中文问题）返回空列表。</p>
     */
    private static List<String> extractIdentifierTokens(String question) {
        List<String> tokens = new ArrayList<>();
        if (question == null || question.isBlank()) {
            return tokens;
        }
        Matcher matcher = IDENTIFIER_TOKEN.matcher(question);
        Set<String> seen = new HashSet<>();
        while (matcher.find() && tokens.size() < IDENTIFIER_TOKEN_LIMIT) {
            String token = matcher.group();
            if (seen.add(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * 抽取中文语义词（CJK 连续串去停用词后保留 2~6 字片段，去重保序）。
     *
     * <p>无分词依赖，采用「停用词切分 + 长度过滤」的轻量启发式：问题
     * 「workflow_metadata表状态是完成的有多少条数据」→ {@code ["状态","完成"]}。
     * 结果仅进入 {@code query_elements.entities}，供 ColdStartDetector/覆盖面观测；
     * 不参与 A 路 keyword（keyword 必须是单 token LIKE 子串，见 buildRequest 注释）。</p>
     */
    private static List<String> extractChineseEntities(String question) {
        List<String> entities = new ArrayList<>();
        if (question == null || question.isBlank()) {
            return entities;
        }
        // 停用词按长度降序替换，避免短词先命中切断长词（如「条数」需先于「条」）
        List<String> stopwords = new ArrayList<>(CJK_STOPWORDS);
        stopwords.sort((a, b) -> b.length() - a.length());
        Matcher matcher = CJK_RUN.matcher(question);
        Set<String> seen = new HashSet<>();
        while (matcher.find() && entities.size() < CHINESE_ENTITY_LIMIT) {
            String run = matcher.group();
            for (String stopword : stopwords) {
                run = run.replace(stopword, "\n");
            }
            for (String segment : run.split("\n+")) {
                String word = segment.trim();
                if (word.length() < CJK_WORD_MIN_LEN || word.length() > CJK_WORD_MAX_LEN) {
                    continue;
                }
                if (seen.add(word) && entities.size() < CHINESE_ENTITY_LIMIT) {
                    entities.add(word);
                }
            }
        }
        return entities;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String question = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.QUESTION, "");
        String dsId = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.DS_ID, "");
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        String intentType = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.INTENT_TYPE, "");
        publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, "正在检索知识库与口径...", "RUNNING");

        String retrievalContext;
        if (retrievalService == null) {
            log.warn("RCAgent 检索节点：RetrievalService 未注入（占位契约缺失），threadId={}", threadId);
            retrievalContext = "[依赖模块 dual-retrieval 未接入] 知识检索服务不可用，本次会话跳过知识上下文。\n";
        } else {
            try {
                RetrievalRequest request = buildRequest(question, dsId, threadId, intentType);
                RetrievalResult result = retrievalService.search(request);
                retrievalContext = assembleContext(result);
                publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                        "检索完成：命中 " + (result.getItems() == null ? 0 : result.getItems().size())
                                + " 条知识", "RUNNING");
            } catch (Exception e) {
                log.warn("RCAgent 检索节点调用失败，降级: {}", RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
                retrievalContext = "[检索服务调用失败] " + RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 500)
                        + "\nSQL 生成将仅依赖用户问题与口径上下文。";
            }
        }
        String finalContext = RcaNodeUtils.truncate(retrievalContext, 6000);
        publish(threadId, RcaWorkflowKeys.RETRIEVAL_CONTEXT, finalContext, "RUNNING");
        return Map.of(RcaWorkflowKeys.RETRIEVAL_CONTEXT, finalContext);
    }

    /** 将检索结果组装为可读上下文文本（表/字段/口径/FAQ 分组），SSE 与 SQL Agent 共用 */
    private String assembleContext(RetrievalResult result) {
        if (result == null) {
            return "[检索无返回]";
        }
        List<RetrievalHit> items = result.getItems();
        if (items == null || items.isEmpty()) {
            String coldTip = result.isColdStart()
                    ? "\n[冷启动提示] " + safe(result.getColdStartReason()) : "";
            return "[检索无命中]" + coldTip;
        }
        StringBuilder sb = new StringBuilder("【检索上下文 - 共 ")
                .append(items.size()).append(" 条】\n");
        int idx = 0;
        for (RetrievalHit hit : items) {
            if (hit == null || idx >= 30) continue;
            idx++;
            sb.append(idx).append(". [").append(hit.sourceValue() == null ? "?" : hit.sourceValue())
                    .append("] ").append(safe(hit.getTitle())).append("\n");
            String content = safe(hit.getContent());
            sb.append(RcaNodeUtils.truncate(content, 600)).append("\n");
            if (hit.getConfidence() != null) {
                sb.append("   (置信度=").append(hit.getConfidence().stripTrailingZeros().toPlainString()).append(")\n");
            }
        }
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private void publish(String threadId, String key, String value, String status) {
        if (eventBus == null || threadId == null) return;
        eventBus.publish(threadId, key, value, status);
    }
}
