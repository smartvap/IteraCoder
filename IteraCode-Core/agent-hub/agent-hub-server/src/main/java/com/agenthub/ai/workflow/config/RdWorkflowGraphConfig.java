package com.agenthub.ai.workflow.config;

import com.agenthub.ai.workflow.node.*;
import com.agenthub.ai.workflow.entity.WorkflowMetadata;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.interceptor.SseStreamingInterceptor;
import com.agenthub.ai.workflow.mapper.WorkflowMetadataMapper;
import com.agenthub.ai.workflow.service.GitProjectService;
import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.alibaba.cloud.ai.graph.state.AgentStateFactory;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.skill.SkillLoader;
import com.agenthub.ai.workflow.tool.NonDevGuardTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import com.alibaba.cloud.ai.graph.action.NodeAction;

/**
 * 智能体研发工作流图配置（阶段一：需求拆解 → 并行推理 → 人工审核 → 代码生成 → Git 提交）
 */
@Slf4j
@Configuration
public class RdWorkflowGraphConfig {

    public static final String MANUAL_REVIEW_NODE = "manual_review";

    @Value("${agenthub.workflow.saver-type:memory}")
    private String saverType;

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${agenthub.workflow.code-storage-path:}")
    private String codeStoragePath;

    @Value("${agenthub.workflow.model-roles.decomposition:qwen3}")
    private String decompModelName;

    @Value("${agenthub.workflow.model-roles.code-generation:qwen3}")
    private String codeGenModelName;

    @Value("${agenthub.workflow.model-roles.merge-result:qwen3}")
    private String mergeModelName;

    @Value("${agenthub.workflow.model-roles.manual-review:qwen3}")
    private String reviewModelName;

    @Value("${agenthub.workflow.model-roles.reasoning-models:gemma2,qwen3}")
    private String reasoningModelNamesStr;

    @jakarta.annotation.PostConstruct
    void initCodeStoragePath() {
        com.agenthub.ai.workflow.tool.CodeProjectWriter.setStoragePath(codeStoragePath);
    }

    private List<String> getReasoningModelNames() {
        return java.util.Arrays.stream(reasoningModelNamesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Bean
    public StateSerializer stateSerializer() {
        AgentStateFactory<OverAllState> stateFactory = OverAllState::new;
        return new SpringAIJacksonStateSerializer(stateFactory);
    }

    @Bean
    @ConditionalOnProperty(name = "agenthub.workflow.saver-type", havingValue = "redis")
    public RedissonClient redissonClient() {
        Config config = new Config();
        var serverConfig = config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort);
        if (redisPassword != null && !redisPassword.isBlank()) {
            serverConfig.setPassword(redisPassword);
        }
        return Redisson.create(config);
    }

    @Bean
    public BaseCheckpointSaver rdWorkflowSaver(StateSerializer stateSerializer,
            javax.sql.DataSource dataSource,
            @org.springframework.beans.factory.annotation.Autowired(required = false) RedissonClient redissonClient) {
        return switch (saverType) {
            case "mysql" -> MysqlSaver.builder()
                    .dataSource(dataSource)
                    .stateSerializer(stateSerializer)
                    .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                    .build();
            case "redis" -> RedisSaver.builder()
                    .redisson(redissonClient)
                    .stateSerializer(stateSerializer)
                    .build();
            default -> new MemorySaver();
        };
    }

    private ChatModel getOrFallback(Map<String, ChatModel> chatModels, String modelName) {
        String beanName = modelName + "ChatModel";
        ChatModel model = chatModels.get(beanName);
        if (model != null) return model;
        log.warn("模型 '{}'（Bean: {}）未在上下文中找到，回退到主模型", modelName, beanName);
        model = chatModels.values().stream().findFirst().orElse(null);
        if (model == null) throw new IllegalStateException("没有可用的 ChatModel Bean，请检查 spring.ai.models 配置");
        return model;
    }

    private static String toAgentKey(String modelName) {
        return "reasoning_" + modelName.trim().replace("-", "_").replace(".", "_");
    }

    @Bean
    public CompiledGraph rdWorkflowCompiledGraph(
            Map<String, ChatModel> chatModels,
            NonDevGuardTools nonDevGuardTools,
            BaseCheckpointSaver rdWorkflowSaver,
            SkillLoader skillLoader,
            WorkflowEventBus eventBus,
            WorkflowMetadataMapper metadataMapper,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            GitProjectService gitService) throws GraphStateException {

        ChatModel decompModel = getOrFallback(chatModels, decompModelName);
        ChatModel codeGenModel = getOrFallback(chatModels, codeGenModelName);
        ChatModel mergeModel = getOrFallback(chatModels, mergeModelName);
        ChatModel reviewModel = getOrFallback(chatModels, reviewModelName);
        log.info("模型分配: decomp={}, codeGen={}, merge={}, review={}",
                decompModel.getClass().getSimpleName(),
                codeGenModel.getClass().getSimpleName(),
                mergeModel.getClass().getSimpleName(),
                reviewModel.getClass().getSimpleName());

        // 时间戳更新节点
        NodeAction updateTimestamp = state -> {
            String tid = RdWorkflowKeys.extractStateText(state, MultiRoundAgentNode.THREAD_ID_KEY, "");
            try {
                WorkflowMetadata meta = metadataMapper.selectById(tid);
                if (meta != null) {
                    meta.setUpdateTime(new Date());
                    metadataMapper.updateById(meta);
                }
            } catch (Exception e) {
                log.warn("更新时间戳失败: threadId={}", tid, e);
            }
            return Map.of();
        };

        // 1. 需求拆解
        String decompInstruction = skillLoader.getInstruction("requirement-analysis");
        if (decompInstruction.isBlank()) {
            decompInstruction = """
                    你是需求拆解智能体。将以下研发需求拆解为可执行的子任务列表。
                    输出格式：子任务编号、描述、优先级、建议使用的模型类型。

                    研发需求：{requirement}

                    审核反馈（如不为空则必须纳入）：{review_feedback}
                    """;
        }
        ReactAgent decompositionAgent = ReactAgent.builder()
                .name("requirement_decomposition")
                .model(decompModel)
                .instruction(decompInstruction)
                .methodTools(nonDevGuardTools)
                .streamingInterceptors(new SseStreamingInterceptor(eventBus, RdWorkflowKeys.DECOMPOSITION_RESULT,
                        Integer.MAX_VALUE, Integer.MAX_VALUE))
                .outputKey(RdWorkflowKeys.DECOMPOSITION_RESULT)
                .enableLogging(true)
                .build();

        // 2. 多模型并行推理
        String archInstruction = skillLoader.getInstruction("architecture-design");
        if (archInstruction.isBlank()) {
            archInstruction = """
                    你是架构设计专家。基于以下任务拆解结果，输出技术栈、模块划分和数据模型设计。
                    拆解结果：{decomposition_result}
                    """;
        }
        String apiInstruction = skillLoader.getInstruction("api-contract-design");
        if (apiInstruction.isBlank()) {
            apiInstruction = """
                    你是 API 设计专家。基于以下任务拆解结果，输出接口契约和业务流程设计。
                    拆解结果：{decomposition_result}
                    """;
        }

        List<ReactAgent> reasoningAgents = new ArrayList<>();
        List<String> modelNames = getReasoningModelNames();
        for (int i = 0; i < modelNames.size(); i++) {
            String modelName = modelNames.get(i).trim();
            ChatModel cm = getOrFallback(chatModels, modelName);
            String key = toAgentKey(modelName);
            String instruction = (i == 0) ? archInstruction : apiInstruction;
            ReactAgent agent = ReactAgent.builder()
                    .name(key)
                    .model(cm)
                    .instruction(instruction)
                    .outputKey(key + "_result")
                    .build();
            reasoningAgents.add(agent);
            log.info("注册并行推理 Agent: name={}, model={}, skill={}",
                    key, modelName, (i == 0) ? "architecture-design" : "api-contract-design");
        }

        // 3. 代码生成
        String codeGenInstruction = skillLoader.getInstruction("code-generation");
        if (codeGenInstruction.isBlank()) {
            codeGenInstruction = """
                    你是正式代码生成智能体。根据已通过人工审核的方案，生成完整可编译的 Java 代码。

                    需求拆解：{decomposition_result}
                    并行推理结果：{parallel_reasoning_result}

                    只输出代码和必要注释。
                    """;
        }
        ReactAgent codeGenerationAgent = ReactAgent.builder()
                .name("code_generation")
                .model(codeGenModel)
                .instruction(codeGenInstruction)
                .outputKey(RdWorkflowKeys.GENERATED_CODE)
                .enableLogging(true)
                .build();

        // KeyStrategy
        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(RdWorkflowKeys.REQUIREMENT, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.DECOMPOSITION_RESULT, new ReplaceStrategy());
            strategies.put(MultiRoundAgentNode.THREAD_ID_KEY, new ReplaceStrategy());
            for (String modelName : getReasoningModelNames()) {
                strategies.put(toAgentKey(modelName) + "_result", new ReplaceStrategy());
            }
            strategies.put(RdWorkflowKeys.PARALLEL_REASONING_RESULT, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.REVIEW_CONTENT, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.REVIEW_DECISION, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.REVIEW_FEEDBACK, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.GENERATED_CODE, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.WORKFLOW_STATUS, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.WORKFLOW_MESSAGE, new ReplaceStrategy());
            strategies.put(CodeProjectWriteNode.CODE_PROJECT_ROOT, new ReplaceStrategy());
            return strategies;
        };

        StateGraph workflow = new StateGraph(keyStrategyFactory);

        // 普通节点
        workflow.addNode("workflow_init", node_async(new WorkflowInitNode(eventBus)));
        workflow.addNode(MANUAL_REVIEW_NODE, node_async(new ManualReviewNode(eventBus, reviewModel)));
        workflow.addNode("merge_parallel_results",
                node_async(new ParallelResultMergeNode(getReasoningModelNames(), mergeModel, eventBus)));
        workflow.addNode("code_project_write", node_async(new CodeProjectWriteNode(eventBus, gitService)));

        // 需求拆解 Agent
        int decompRounds = skillLoader.getMetadata("requirement-analysis") != null
                ? skillLoader.getMetadata("requirement-analysis").getMaxRounds() : 0;
        if (decompRounds > 1) {
            log.info("Skill [requirement-analysis] 使用多轮循环模式: {} rounds", decompRounds);
            workflow.addNode(decompositionAgent.name(),
                    node_async(new MultiRoundAgentNode(decompModel, decompInstruction,
                            decompRounds, RdWorkflowKeys.DECOMPOSITION_RESULT, eventBus)));
        } else {
            workflow.addNode(decompositionAgent.name(), decompositionAgent.asNode(true, false));
        }

        // 并行推理 Agent
        int archRounds = skillLoader.getMetadata("architecture-design") != null
                ? skillLoader.getMetadata("architecture-design").getMaxRounds() : 0;
        int apiRounds = skillLoader.getMetadata("api-contract-design") != null
                ? skillLoader.getMetadata("api-contract-design").getMaxRounds() : 0;
        for (int i = 0; i < reasoningAgents.size(); i++) {
            ReactAgent agent = reasoningAgents.get(i);
            String skillName = (i == 0) ? "architecture-design" : "api-contract-design";
            int rounds = (i == 0) ? archRounds : apiRounds;
            String instruction = (i == 0) ? archInstruction : apiInstruction;
            String modelName = modelNames.get(i).trim();
            ChatModel cm = getOrFallback(chatModels, modelName);
            int effectiveRounds = Math.max(rounds, 1);
            log.info("Skill [{}] MultiRoundAgentNode: {} rounds, model={}", skillName, effectiveRounds, modelName);
            workflow.addNode(agent.name(),
                    node_async(new MultiRoundAgentNode(cm, instruction,
                            effectiveRounds, toAgentKey(modelName) + "_result", eventBus)));
        }

        // 代码生成 Agent
        int codeGenRounds = skillLoader.getMetadata("code-generation") != null
                ? skillLoader.getMetadata("code-generation").getMaxRounds() : 0;
        if (codeGenRounds > 1) {
            log.info("Skill [code-generation] 使用多轮循环模式: {} rounds", codeGenRounds);
            workflow.addNode(codeGenerationAgent.name(),
                    node_async(new MultiRoundAgentNode(codeGenModel, codeGenInstruction,
                            codeGenRounds, RdWorkflowKeys.GENERATED_CODE, eventBus)));
        } else {
            workflow.addNode(codeGenerationAgent.name(), codeGenerationAgent.asNode(true, false));
        }

        // ==== 主流程 ====
        workflow.addEdge(StateGraph.START, "workflow_init");
        workflow.addEdge("workflow_init", decompositionAgent.name());

        // 归一化
        workflow.addNode("decomp_normalize", node_async(state -> {
            Object v = state.value(RdWorkflowKeys.DECOMPOSITION_RESULT).orElse("");
            String text = v instanceof org.springframework.ai.chat.messages.AssistantMessage msg
                    ? msg.getText() : v.toString();
            return Map.of(RdWorkflowKeys.DECOMPOSITION_RESULT, text);
        }));
        workflow.addEdge(decompositionAgent.name(), "decomp_normalize");

        // 门控 + 时间戳
        workflow.addNode("decomposition_gate", node_async(new DecompositionGateNode(eventBus)));
        workflow.addNode("ts_decomp_done", node_async(updateTimestamp));
        workflow.addEdge("decomp_normalize", "ts_decomp_done");
        workflow.addConditionalEdges(
                "ts_decomp_done",
                edge_async(state -> {
                    String result = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.DECOMPOSITION_RESULT, "");
                    String tid = RdWorkflowKeys.extractStateText(state, MultiRoundAgentNode.THREAD_ID_KEY, "");
                    if (RdWorkflowKeys.isNotDevReq(result)) return "not_dev_req";
                    Boolean isDev = NonDevGuardTools.getAndClear(tid);
                    if (Boolean.FALSE.equals(isDev)) {
                        log.warn("非研发需求拦截: threadId={}", tid);
                        return "not_dev_req";
                    }
                    return "proceed";
                }),
                Map.of("not_dev_req", StateGraph.END, "proceed", "decomposition_gate")
        );

        // 并行推理扇出
        for (ReactAgent agent : reasoningAgents) {
            workflow.addEdge("decomposition_gate", agent.name());
        }
        for (ReactAgent agent : reasoningAgents) {
            workflow.addEdge(agent.name(), "merge_parallel_results");
        }
        workflow.addEdge("merge_parallel_results", "ts_reasoning_done");
        workflow.addNode("ts_reasoning_done", node_async(updateTimestamp));
        workflow.addEdge("ts_reasoning_done", MANUAL_REVIEW_NODE);

        // 人工审核条件分支
        workflow.addConditionalEdges(
                MANUAL_REVIEW_NODE,
                edge_async(state -> {
                    String decision = state.value(RdWorkflowKeys.REVIEW_DECISION, "TERMINATED").toString();
                    return switch (decision) {
                        case "APPROVED" -> "approved";
                        case "SENT_BACK" -> "sent_back";
                        default -> "terminated";
                    };
                }),
                Map.of(
                        "approved", codeGenerationAgent.name(),
                        "sent_back", decompositionAgent.name(),
                        "terminated", StateGraph.END
                )
        );

        // 代码生成 → 写出 + Git 提交 → END
        workflow.addEdge(codeGenerationAgent.name(), "ts_code_done");
        workflow.addNode("ts_code_done", node_async(updateTimestamp));
        workflow.addEdge("ts_code_done", "code_project_write");
        workflow.addEdge("code_project_write", StateGraph.END);

        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(rdWorkflowSaver).build())
                .interruptAfter(MANUAL_REVIEW_NODE)
                .interruptBeforeEdge(true)
                .build();

        CompiledGraph compiledGraph = workflow.compile(compileConfig);
        log.info("智能体研发工作流 StateGraph 编译完成（推理模型数: {}）", getReasoningModelNames().size());
        return compiledGraph;
    }
}
