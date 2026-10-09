package com.agenthub.ai.rcagent.engine.config;

import com.agenthub.ai.rcagent.crosssource.CrossSourceExecutionService;
import com.agenthub.ai.rcagent.crosssource.CrossSourceMetadataAggregator;
import com.agenthub.ai.rcagent.crosssource.CrossSourcePlanViewAdapter;
import com.agenthub.ai.rcagent.crosssource.CrossSourceSqlGuardAdapter;
import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.crosssource.support.CrossSourceStateSupport;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.mapper.RcaWorkflowMetadataMapper;
import com.agenthub.ai.rcagent.engine.node.RcaAnswerNode;
import com.agenthub.ai.rcagent.engine.node.RcaAttributionNode;
import com.agenthub.ai.rcagent.engine.node.RcaCrossSourceExecuteNode;
import com.agenthub.ai.rcagent.engine.node.RcaCrossSourceGenerateNode;
import com.agenthub.ai.rcagent.engine.node.RcaCrossSourcePlanNode;
import com.agenthub.ai.rcagent.engine.node.RcaCrossSourceValidateNode;
import com.agenthub.ai.rcagent.engine.node.RcaExecuteNode;
import com.agenthub.ai.rcagent.engine.node.RcaFeedbackNode;
import com.agenthub.ai.rcagent.engine.node.RcaIntentNode;
import com.agenthub.ai.rcagent.engine.node.RcaIntentNodeAdapter;
import com.agenthub.ai.rcagent.engine.node.RcaQueryAnswerNode;
import com.agenthub.ai.rcagent.engine.node.RcaResultCheckNode;
import com.agenthub.ai.rcagent.engine.node.RcaRetrievalNode;
import com.agenthub.ai.rcagent.engine.node.RcaRetrievalNodeAdapter;
import com.agenthub.ai.rcagent.engine.node.RcaReviewNode;
import com.agenthub.ai.rcagent.engine.node.RcaSqlGenerateNode;
import com.agenthub.ai.rcagent.engine.node.RcaSqlPlanNode;
import com.agenthub.ai.rcagent.engine.node.RcaUpdateTimestampNode;
import com.agenthub.ai.rcagent.engine.node.RcaValidateNode;
import com.agenthub.ai.rcagent.engine.service.RcaWorkflowService;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataDictionaryLookup;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataQuestionDetector;
import com.agenthub.ai.rcagent.engine.tool.RcaSqlGuardTools;
import com.agenthub.ai.rcagent.retrieval.config.RetrievalProperties;
import com.agenthub.ai.rcagent.retrieval.service.RetrievalService;
import com.agenthub.ai.rcagent.sqlguard.execute.SqlExecutionGuardService;
import com.agenthub.ai.rcagent.sqlguard.normalize.SqlDraftNormalizer;
import com.agenthub.ai.rcagent.sqlguard.service.SqlGuardService;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.node.MultiRoundAgentNode;
import com.agenthub.ai.workflow.skill.SkillLoader;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import com.alibaba.cloud.ai.graph.action.NodeAction;

/**
 * RCAgent 工作流 StateGraph 配置（rd 平行实例，第二个 CompiledGraph Bean，与 rd 完全隔离）。
 * <p>
 * 流程：意图识别 → 检索 → SQL 规划 → SQL 生成 → 验证分级 →
 * （LOW 自动 / MEDIUM·HIGH 人工审核 interruptAfter rcagent_review）→ 只读执行 →
 * 结果校验摘要 → （ATTRIBUTION 归因衔接 → 反馈提示）。
 * checkpoint 通过 rcaWorkflowSaver 落库（GRAPH_THREAD/GRAPH_CHECKPOINT 同表，thread_name 天然按 UUID 隔离）。
 * 节点状态键全部使用 {@link RcaWorkflowKeys} 常量（FR-BE-003），无魔法字符串。</p>
 */
@Slf4j
@Configuration
public class RcaWorkflowGraphConfig {

    /** 人工审核节点名（interruptAfter 目标，RcaWorkflowService.resume/terminate 复用） */
    public static final String RCA_REVIEW_NODE = "rcagent_review";
    public static final String RCA_INTENT_NODE = "rcagent_intent";
    public static final String RCA_RETRIEVAL_NODE = "rcagent_retrieval";
    public static final String RCA_SQL_PLAN_NODE = "rcagent_sql_plan";
    public static final String RCA_SQL_GENERATE_NODE = "rcagent_sql_generate";
    public static final String RCA_VALIDATE_NODE = "rcagent_validate";
    public static final String RCA_EXECUTE_NODE = "rcagent_execute";
    /** CHAT 意图答题节点（检索后、结果校验前，产出 answer） */
    public static final String RCA_ANSWER_NODE = "rcagent_answer";
    /** QUERY 意图查数作答节点（执行后、结果校验前，产出 query_answer） */
    public static final String RCA_QUERY_ANSWER_NODE = "rcagent_query_answer";
    public static final String RCA_RESULT_CHECK_NODE = "rcagent_result_check";
    public static final String RCA_ATTRIBUTION_NODE = "rcagent_attribution";
    public static final String RCA_FEEDBACK_NODE = "rcagent_feedback";
    /** 跨源规划节点（module-003 新增，仅 QUERY + 多源 + enabled 进入） */
    public static final String RCA_CROSS_SOURCE_PLAN_NODE = "rcagent_cross_source_plan";
    /** 跨源逐源 SQL 生成节点（module-003 新增） */
    public static final String RCA_CROSS_SOURCE_GENERATE_NODE = "rcagent_cross_source_generate";
    /** 跨源逐源验证节点（module-003 新增） */
    public static final String RCA_CROSS_SOURCE_VALIDATE_NODE = "rcagent_cross_source_validate";
    /** 跨源执行节点（module-003 新增：逐源执行 + 半连接下推 + 内存 join + 合并） */
    public static final String RCA_CROSS_SOURCE_EXECUTE_NODE = "rcagent_cross_source_execute";

    @Value("${agenthub.rcagent.saver-type:memory}")
    private String saverType;

    @Value("${agenthub.rcagent.model-roles.intent:}")
    private String intentModelName;

    @Value("${agenthub.rcagent.model-roles.sql-plan:}")
    private String sqlPlanModelName;

    @Value("${agenthub.rcagent.model-roles.sql-generate:}")
    private String sqlGenerateModelName;

    @Value("${agenthub.rcagent.model-roles.answer:}")
    private String answerModelName;

    /** 从 yml 读取的模型名缺失时回退主模型（getOrFallback 兜底） */
    private ChatModel getOrFallback(Map<String, ChatModel> chatModels, String modelName) {
        String effective = (modelName == null || modelName.isBlank()) ? "" : modelName;
        if (!effective.isBlank()) {
            ChatModel model = chatModels.get(effective + "ChatModel");
            if (model != null) {
                return model;
            }
            log.warn("RCAgent 模型 '{}' 未在上下文中找到，回退到主模型", modelName);
        }
        ChatModel fallback = chatModels.values().stream().findFirst().orElse(null);
        if (fallback == null) {
            throw new IllegalStateException("没有可用的 ChatModel Bean，请检查 spring.ai.models 配置");
        }
        return fallback;
    }

    /**
     * RCAgent 独立 saver（与 rdWorkflowSaver 并存，thread_name 隔离）。
     */
    @Bean
    public BaseCheckpointSaver rcaWorkflowSaver(
            StateSerializer stateSerializer,
            javax.sql.DataSource dataSource,
            @Autowired(required = false)
            org.redisson.api.RedissonClient redissonClient) {
        return switch (saverType) {
            case "mysql" -> MysqlSaver.builder()
                    .dataSource(dataSource)
                    .stateSerializer(stateSerializer)
                    .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                    .build();
            case "redis" -> redissonClient != null
                    ? RedisSaver.builder().redisson(redissonClient).stateSerializer(stateSerializer).build()
                    : new MemorySaver();
            default -> new MemorySaver();
        };
    }

    @Bean
    public CompiledGraph rcaWorkflowCompiledGraph(
            Map<String, ChatModel> chatModels,
            WorkflowEventBus eventBus,
            SkillLoader skillLoader,
            BaseCheckpointSaver rcaWorkflowSaver,
            RcaWorkflowMetadataMapper metadataMapper,
            SqlDraftNormalizer sqlDraftNormalizer,
            @Autowired(required = false) RetrievalService retrievalService,
            RetrievalProperties retrievalProperties,
            @Autowired(required = false) SqlGuardService sqlGuardService,
            @Autowired(required = false) SqlExecutionGuardService sqlExecutionGuardService,
            @Autowired(required = false) RcaMetadataDictionaryLookup rcaMetadataDictionaryLookup,
            CrossSourceProperties crossSourceProperties,
            @Autowired(required = false) CrossSourceMetadataAggregator crossSourceMetadataAggregator,
            @Autowired(required = false) CrossSourceSqlGuardAdapter crossSourceSqlGuardAdapter,
            @Autowired(required = false) CrossSourcePlanViewAdapter crossSourcePlanViewAdapter,
            @Autowired(required = false) CrossSourceExecutionService crossSourceExecutionService)
            throws GraphStateException {

        ChatModel intentModel = getOrFallback(chatModels, intentModelName);
        ChatModel sqlPlanModel = getOrFallback(chatModels, sqlPlanModelName);
        ChatModel sqlGenerateModel = getOrFallback(chatModels, sqlGenerateModelName);
        ChatModel answerModel = getOrFallback(chatModels, answerModelName);
        // L5 埋点字段：sql-generate 角色模型标识（配置缺失时回退实现类简名，FR-BE-016/SC-009）
        String effectiveSqlGenerateModelName = (sqlGenerateModelName == null || sqlGenerateModelName.isBlank())
                ? sqlGenerateModel.getClass().getSimpleName()
                : sqlGenerateModelName;
        log.info("RCAgent 模型分配: intent={}, sqlPlan={}, sqlGenerate={}, answer={}",
                intentModel.getClass().getSimpleName(),
                sqlPlanModel.getClass().getSimpleName(),
                sqlGenerateModel.getClass().getSimpleName(),
                answerModel.getClass().getSimpleName());

        // 读取 skills/rc 技能（不存在时 fallback 默认指令）
        String generateSkill = skillText(skillLoader, "rc-sql-generate");
        String generateInstruction = generateSkill.isBlank() ? """
                你是查数 SQL 生成器。基于 SQL 规划与检索上下文生成只读 SELECT 语句。
                严禁 DML/DDL；默认方言 MySQL；禁止 SELECT *。
                """ : generateSkill;

        // CHAT 答题技能（缺失时由节点内联兜底，保证提示词可用）
        String answerSkill = skillText(skillLoader, "rc-answer");
        String answerInstruction = answerSkill.isBlank() ? """
                你是 RCAgent 知识问答助手。仅依据检索证据回答问题，证据不足时如实说明"检索证据未覆盖，无法确定"，
                禁止编造；回答简洁、直接针对用户所问要点，可引用证据中的表/列注释作为依据，使用中文。
                """ : answerSkill;

        // QUERY 查数结论技能（缺失时内联兜底，与 rc-query-answer.md 语义一致）
        String queryAnswerSkill = skillText(skillLoader, "rc-query-answer");
        String queryAnswerInstruction = queryAnswerSkill.isBlank() ? """
                你是查数结论生成器。请仅依据【查询结果】用一句话中文结论直接回答【用户问题】。
                - 结论中的数值、条数、维度必须与【查询结果】完全一致，禁止换算、取整或推断；结果为空时如实说明"未查询到符合条件的数据"。
                - 【查询口径】仅供理解统计口径，严禁在结论中复述或引用。
                - 禁止输出 SQL 原文、库名/连接串、账号、密码、Token、表结构表达式（如 COUNT(*)）；禁止复述"风险等级/行数/列"等内部字段名。
                - 只输出结论本身，不要输出标题、编号、Markdown 代码块或任何前后缀。
                """ : queryAnswerSkill;

        // 跨源规划技能（module-003；缺失时内联兜底，保证提示词可用）
        String crossPlanSkill = skillText(skillLoader, "rc-cross-source-join-plan");
        String crossPlanInstruction = crossPlanSkill.isBlank() ? """
                你是跨源关联规划器（跨数据源无法单条 SQL 关联）。依据用户问题与逐源元数据证据，输出结构化关联计划 JSON。
                仅输出 JSON（无代码围栏、无解释）；join key 必须来自元数据证据中真实存在的列；禁止输出 SQL、禁止虚构表/列。
                """ : crossPlanSkill;

        // 跨源逐源 SQL 生成技能（module-003；缺失时内联兜底）
        String crossGenerateSkill = skillText(skillLoader, "rc-cross-source-join-generate");
        String crossGenerateInstruction = crossGenerateSkill.isBlank() ? """
                你是单源只读 SQL 生成器。为一个数据源生成一条只读 SELECT：严禁 DML/DDL、严禁 SELECT *、
                严禁多语句、严禁跨源 join（跨源关联由应用层完成）；投影列必须使用语义化 AS 别名，过滤条件下推；
                必须输出计划指定的关联键列（保持可被外层引用）；仅输出纯 SQL。
                """ : crossGenerateSkill;

        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : new String[]{
                    RcaWorkflowKeys.QUESTION, RcaWorkflowKeys.DS_ID,
                    RcaWorkflowKeys.DS_IDS, RcaWorkflowKeys.SYSTEM_CODE,
                    RcaWorkflowKeys.INTENT_TYPE, RcaWorkflowKeys.INTENT_RESULT,
                    RcaWorkflowKeys.RETRIEVAL_CONTEXT, RcaWorkflowKeys.SQL_PLAN,
                    RcaWorkflowKeys.SQL_GENERATE, RcaWorkflowKeys.SQL_VALIDATE_REPORT,
                    RcaWorkflowKeys.RISK_LEVEL, RcaWorkflowKeys.REVIEW_CONTENT,
                    RcaWorkflowKeys.REVIEW_DECISION, RcaWorkflowKeys.REVIEW_FEEDBACK,
                    RcaWorkflowKeys.QUERY_RESULT, RcaWorkflowKeys.ATTRIBUTION_RESULT,
                    RcaWorkflowKeys.ANSWER, RcaWorkflowKeys.QUERY_ANSWER,
                    RcaWorkflowKeys.RESULT_SUMMARY,
                    RcaWorkflowKeys.WORKFLOW_STATUS, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                    // module-003 跨源 5 键（附加键，须注册 ReplaceStrategy）
                    RcaWorkflowKeys.JOIN_HINT, RcaWorkflowKeys.JOIN_PLAN,
                    RcaWorkflowKeys.SOURCE_QUERY_PLANS, RcaWorkflowKeys.SOURCE_QUERY_RESULTS,
                    RcaWorkflowKeys.CROSS_SOURCE_NOTE
            }) {
                strategies.put(key, new ReplaceStrategy());
            }
            // _thread_id_ 用于 SSE 推送，必须在节点间保留
            strategies.put(MultiRoundAgentNode.THREAD_ID_KEY, new ReplaceStrategy());
            return strategies;
        };

        StateGraph graph = new StateGraph(keyStrategyFactory);

        // ===== 节点注册 =====
        graph.addNode(RCA_INTENT_NODE,
                node_async(new RcaIntentNodeAdapter(eventBus, new RcaIntentNode(eventBus, intentModel),
                        RcaMetadataQuestionDetector.INSTANCE)));
        graph.addNode(RCA_RETRIEVAL_NODE,
                node_async(new RcaRetrievalNodeAdapter(eventBus,
                        new RcaRetrievalNode(eventBus, retrievalService, retrievalProperties),
                        RcaMetadataQuestionDetector.INSTANCE,
                        rcaMetadataDictionaryLookup)));
        graph.addNode(RCA_SQL_PLAN_NODE,
                node_async(new RcaSqlPlanNode(eventBus, sqlPlanModel)));
        int generateRounds = skillLoader.getSkill("rc-sql-generate") != null
                ? Math.max(1, skillLoader.getSkill("rc-sql-generate").getMetadata().getMaxRounds()) : 2;
        graph.addNode(RCA_SQL_GENERATE_NODE,
                node_async(new RcaSqlGenerateNode(eventBus, sqlGenerateModel, generateInstruction,
                        generateRounds, new RcaSqlGuardTools(sqlGuardService), sqlDraftNormalizer,
                        effectiveSqlGenerateModelName)));
        graph.addNode(RCA_VALIDATE_NODE,
                node_async(new RcaValidateNode(eventBus, sqlGuardService)));
        graph.addNode(RCA_REVIEW_NODE,
                node_async(new RcaReviewNode(eventBus)));
        graph.addNode(RCA_EXECUTE_NODE,
                node_async(new RcaExecuteNode(eventBus, sqlExecutionGuardService)));
        graph.addNode(RCA_ANSWER_NODE,
                node_async(new RcaAnswerNode(eventBus, answerModel, answerInstruction)));
        graph.addNode(RCA_QUERY_ANSWER_NODE,
                node_async(new RcaQueryAnswerNode(eventBus, answerModel, queryAnswerInstruction)));
        // ===== 跨源 join 节点（module-003：仅 QUERY + DS_IDS.size()>1 + enabled 时经条件边进入） =====
        graph.addNode(RCA_CROSS_SOURCE_PLAN_NODE,
                node_async(new RcaCrossSourcePlanNode(eventBus, sqlPlanModel, crossPlanInstruction,
                        crossSourceMetadataAggregator, crossSourceProperties)));
        graph.addNode(RCA_CROSS_SOURCE_GENERATE_NODE,
                node_async(new RcaCrossSourceGenerateNode(eventBus, sqlGenerateModel, crossGenerateInstruction,
                        sqlDraftNormalizer, crossSourceSqlGuardAdapter, crossSourceProperties)));
        graph.addNode(RCA_CROSS_SOURCE_VALIDATE_NODE,
                node_async(new RcaCrossSourceValidateNode(eventBus, crossSourceSqlGuardAdapter,
                        crossSourcePlanViewAdapter)));
        graph.addNode(RCA_CROSS_SOURCE_EXECUTE_NODE,
                node_async(new RcaCrossSourceExecuteNode(eventBus, crossSourceExecutionService,
                        crossSourceProperties)));
        graph.addNode(RCA_RESULT_CHECK_NODE,
                node_async(new RcaResultCheckNode(eventBus)));
        graph.addNode(RCA_ATTRIBUTION_NODE,
                node_async(new RcaAttributionNode(eventBus)));
        graph.addNode(RCA_FEEDBACK_NODE,
                node_async(new RcaFeedbackNode(eventBus)));

        // 失败收尾节点（BLOCK 拦截）
        graph.addNode("rcagent_blocked_end", node_async((OverAllState state) -> {
            String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                    .map(Object::toString).orElse(null);
            String blockedReason = RcaWorkflowKeys.extractStateText(
                    state, RcaWorkflowKeys.SQL_VALIDATE_REPORT, "SQL 被安全体系拦截");
            if (eventBus != null && threadId != null) {
                eventBus.publish(threadId, RcaWorkflowKeys.WORKFLOW_STATUS,
                        "FAILED — SQL 被安全体系拦截", "FAILED");
                eventBus.publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE,
                        "SQL 验证失败（BLOCK）：已拦截，不进入执行。请修改 SQL 后重试。", "FAILED");
            }
            // 跨线程 BLOCK 标记：BLOCK 收尾节点在异步工作流线程执行，pushFinalState 依赖的
            // lastOutput.node()/checkpoint risk_level 均可能不可靠，故显式标记，供终态判定可靠命中 FAILED（FR-BE-007）。
            if (threadId != null) {
                RcaWorkflowService.markBlocked(threadId);
            }
            log.info("RCAgent BLOCK 拦截结束: threadId={}, reason={}", threadId,
                    RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SQL_VALIDATE_REPORT, "").length() > 120
                            ? "..." : blockedReason);
            return Map.of(
                    RcaWorkflowKeys.WORKFLOW_STATUS, "FAILED",
                    RcaWorkflowKeys.WORKFLOW_MESSAGE, "SQL 验证失败（BLOCK）：已拦截，不进入执行。请修改 SQL 后重试。"
            );
        }));

        // ===== 时间戳穿插（体验进度更新） =====
        graph.addNode("rcagent_ts_intent_done",
                node_async(new RcaUpdateTimestampNode(metadataMapper, "rcagent_ts_intent_done")));
        graph.addNode("rcagent_ts_retrieval_done",
                node_async(new RcaUpdateTimestampNode(metadataMapper, "rcagent_ts_retrieval_done")));
        graph.addNode("rcagent_ts_plan_done",
                node_async(new RcaUpdateTimestampNode(metadataMapper, "rcagent_ts_plan_done")));
        graph.addNode("rcagent_ts_generate_done",
                node_async(new RcaUpdateTimestampNode(metadataMapper, "rcagent_ts_generate_done")));
        graph.addNode("rcagent_ts_validate_done",
                node_async(new RcaUpdateTimestampNode(metadataMapper, "rcagent_ts_validate_done")));
        graph.addNode("rcagent_ts_execute_done",
                node_async(new RcaUpdateTimestampNode(metadataMapper, "rcagent_ts_execute_done")));

        // ===== 边 =====
        graph.addEdge(StateGraph.START, RCA_INTENT_NODE);
        graph.addEdge(RCA_INTENT_NODE, "rcagent_ts_intent_done");
        graph.addEdge("rcagent_ts_intent_done", RCA_RETRIEVAL_NODE);
        graph.addEdge(RCA_RETRIEVAL_NODE, "rcagent_ts_retrieval_done");
        // 检索后分流：CHAT 无 SQL，先经答题节点产出 answer 再进入结果校验摘要；QUERY/ATTRIBUTION 进入 SQL 规划；
        // module-003：QUERY 且候选源 >1 且 cross-source.enabled=true → 跨源规划节点（单源/ATTRIBUTION/disabled 走 sql，零回归）
        graph.addConditionalEdges(
                "rcagent_ts_retrieval_done",
                edge_async(state -> {
                    String intent = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.INTENT_TYPE, "");
                    if ("CHAT".equals(intent)) {
                        return "chat";
                    }
                    return CrossSourceStateSupport.isCrossSourceQuery(state, crossSourceProperties) ? "cross" : "sql";
                }),
                Map.of(
                        "chat", RCA_ANSWER_NODE,
                        "sql", RCA_SQL_PLAN_NODE,
                        "cross", RCA_CROSS_SOURCE_PLAN_NODE
                )
        );
        // 答题节点输出 answer 后交结果校验节点消费（LLM 失败无 answer 时结果节点回退检索证据旧行为）
        graph.addEdge(RCA_ANSWER_NODE, RCA_RESULT_CHECK_NODE);
        // ===== 跨源链（module-003）：规划后条件边 + 生成→校验 + 校验/执行接既有时间戳节点 =====
        graph.addConditionalEdges(
                RCA_CROSS_SOURCE_PLAN_NODE,
                edge_async(state -> {
                    if (CrossSourceStateSupport.hasText(state, RcaWorkflowKeys.JOIN_PLAN)) {
                        return "continue";
                    }
                    // 用户显式指定关联键校验失败：明确 FAILED 终态（不降级单源、不生成可执行 SQL、不进人工审核）
                    if ("FAILED".equals(RcaWorkflowKeys.extractStateText(
                            state, RcaWorkflowKeys.WORKFLOW_STATUS, ""))) {
                        return "failed";
                    }
                    return "degrade";
                }),
                Map.of(
                        "continue", RCA_CROSS_SOURCE_GENERATE_NODE,
                        "degrade", RCA_SQL_PLAN_NODE,
                        "failed", StateGraph.END
                )
        );
        graph.addEdge(RCA_CROSS_SOURCE_GENERATE_NODE, RCA_CROSS_SOURCE_VALIDATE_NODE);
        graph.addEdge(RCA_CROSS_SOURCE_VALIDATE_NODE, "rcagent_ts_validate_done");
        graph.addEdge(RCA_CROSS_SOURCE_EXECUTE_NODE, "rcagent_ts_execute_done");
        graph.addEdge(RCA_SQL_PLAN_NODE, "rcagent_ts_plan_done");
        graph.addEdge("rcagent_ts_plan_done", RCA_SQL_GENERATE_NODE);
        graph.addEdge(RCA_SQL_GENERATE_NODE, "rcagent_ts_generate_done");
        graph.addEdge("rcagent_ts_generate_done", RCA_VALIDATE_NODE);
        graph.addEdge(RCA_VALIDATE_NODE, "rcagent_ts_validate_done");
        // 验证后分流：BLOCK 拦截 / LOW 自动 / MEDIUM·HIGH 人工审核；跨源 LOW 走跨源执行节点（新增 low_cross）
        graph.addConditionalEdges(
                "rcagent_ts_validate_done",
                edge_async(state -> {
                    // 生成阶段已判 FAILED（跨源逐源生成失败）→ 直接终态（不进人工审核，避免误置 WAITING_REVIEW）
                    if ("FAILED".equals(RcaWorkflowKeys.extractStateText(
                            state, RcaWorkflowKeys.WORKFLOW_STATUS, ""))) {
                        return "failed";
                    }
                    String risk = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RISK_LEVEL, "");
                    if ("BLOCK".equals(risk)) {
                        return "blocked";
                    }
                    if ("LOW".equals(risk)) {
                        return isCrossChain(state) ? "low_cross" : "low";
                    }
                    return "review";
                }),
                Map.of(
                        "blocked", "rcagent_blocked_end",
                        "low", RCA_EXECUTE_NODE,
                        "review", RCA_REVIEW_NODE,
                        "low_cross", RCA_CROSS_SOURCE_EXECUTE_NODE,
                        "failed", StateGraph.END
                )
        );
        graph.addEdge("rcagent_blocked_end", StateGraph.END);
        // 审核后分流：APPROVED 执行 / SENT_BACK 回退重生成 / TERMINATED 终止；
        // module-003：跨源会话分别走跨源执行/跨源生成节点（approved_cross / sent_back_cross）
        graph.addConditionalEdges(
                RCA_REVIEW_NODE,
                edge_async(state -> {
                    String decision = state.value(RcaWorkflowKeys.REVIEW_DECISION, "")
                            .toString();
                    boolean cross = isCrossChain(state);
                    return switch (decision) {
                        case "APPROVED" -> cross ? "approved_cross" : "approved";
                        case "SENT_BACK" -> cross ? "sent_back_cross" : "sent_back";
                        case "TERMINATED" -> "terminated";
                        default -> "terminated";
                    };
                }),
                Map.of(
                        "approved", RCA_EXECUTE_NODE,
                        "sent_back", RCA_SQL_GENERATE_NODE,
                        "terminated", StateGraph.END,
                        "approved_cross", RCA_CROSS_SOURCE_EXECUTE_NODE,
                        "sent_back_cross", RCA_CROSS_SOURCE_GENERATE_NODE
                )
        );
        graph.addEdge(RCA_EXECUTE_NODE, "rcagent_ts_execute_done");
        // QUERY/ATTRIBUTION 执行后先经查数作答节点产出结论（非 QUERY 时节点内 no-op），再交结果校验节点
        graph.addEdge("rcagent_ts_execute_done", RCA_QUERY_ANSWER_NODE);
        graph.addEdge(RCA_QUERY_ANSWER_NODE, RCA_RESULT_CHECK_NODE);
        // 结果校验后分流：归因追问进入归因衔接；其余直接反馈提示收尾
        graph.addConditionalEdges(
                RCA_RESULT_CHECK_NODE,
                edge_async(state -> {
                    String intent = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.INTENT_TYPE, "");
                    return "ATTRIBUTION".equals(intent) ? "attribution" : "done";
                }),
                Map.of(
                        "attribution", RCA_ATTRIBUTION_NODE,
                        "done", RCA_FEEDBACK_NODE
                )
        );
        graph.addEdge(RCA_ATTRIBUTION_NODE, RCA_FEEDBACK_NODE);
        graph.addEdge(RCA_FEEDBACK_NODE, StateGraph.END);

        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(rcaWorkflowSaver).build())
                .interruptAfter(RCA_REVIEW_NODE)
                .interruptBeforeEdge(true)
                .build();

        CompiledGraph compiledGraph = graph.compile(compileConfig);
        log.info("RCAgent 工作流 StateGraph 编译完成");
        return compiledGraph;
    }

    /**
     * 是否处于**跨源链路**（用于验证后/审核后条件边的目标选择）。
     *
     * <p>判定 = 跨源场景（{@code QUERY} + 多源 + enabled）**且**存在跨源产物键
     * （{@code join_plan} 或 {@code source_query_plans}）。多一个「产物键存在」条件是为了覆盖
     * 「跨源规划降级 → 走既有单源链路」场景：此时会话仍是多源，但 SQL 由单源节点产出，
     * 执行/审核目标必须走既有单源节点（否则会误入跨源执行节点）。</p>
     */
    private static boolean isCrossChain(OverAllState state) {
        return CrossSourceStateSupport.hasText(state, RcaWorkflowKeys.JOIN_PLAN)
                || CrossSourceStateSupport.hasText(state, RcaWorkflowKeys.SOURCE_QUERY_PLANS);
    }

    /** 读取 skills/rc 技能指令（不存在返回空串） */
    private static String skillText(SkillLoader skillLoader, String name) {        if (skillLoader == null) return "";
        var skill = skillLoader.getSkill(name);
        return skill != null && skill.getInstruction() != null ? skill.getInstruction() : "";
    }
}
