package com.agenthub.ai.workflow.config;

import com.agenthub.ai.workflow.node.*;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 智能体研发工作流图配置（阶段一：需求拆解 → 多模型并行推理 → 人工审核）
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

    @Value("${agenthub.workflow.model-roles.decomposition:qwen3}")
    private String decompModelName;

    @Value("${agenthub.workflow.reasoning-models:gemma2,qwen3}")
    private String reasoningModelNamesStr;

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
            BaseCheckpointSaver rdWorkflowSaver,
            SkillLoader skillLoader,
            WorkflowEventBus eventBus) throws GraphStateException {

        ChatModel decompModel = getOrFallback(chatModels, decompModelName);

        // 1. 需求拆解智能体
        String decompInstruction = skillLoader.getInstruction("requirement-analysis");
        if (decompInstruction.isBlank()) {
            decompInstruction = """
                    你是需求拆解智能体。将以下研发需求拆解为可执行的子任务列表。
                    输出格式：子任务编号、描述、优先级、建议使用的模型类型。

                    研发需求：{requirement}
                    """;
        }
        ReactAgent decompositionAgent = ReactAgent.builder()
                .name("requirement_decomposition")
                .model(decompModel)
                .instruction(decompInstruction)
                .outputKey(RdWorkflowKeys.DECOMPOSITION_RESULT)
                .enableLogging(true)
                .build();

        // 2. 多模型并行推理（架构设计 + API 契约设计）
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
            strategies.put(RdWorkflowKeys.WORKFLOW_STATUS, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.WORKFLOW_MESSAGE, new ReplaceStrategy());
            return strategies;
        };

        StateGraph workflow = new StateGraph(keyStrategyFactory);

        // 节点注册
        workflow.addNode("workflow_init", node_async(new WorkflowInitNode(eventBus)));
        workflow.addNode(MANUAL_REVIEW_NODE, node_async(new ManualReviewNode(eventBus, decompModel)));
        workflow.addNode("merge_parallel_results",
                node_async(new ParallelResultMergeNode(getReasoningModelNames(), decompModel, eventBus)));
        workflow.addNode("decomposition_gate", node_async(new DecompositionGateNode(eventBus)));

        // 需求拆解 Agent
        int decompRounds = skillLoader.getMetadata("requirement-analysis") != null
                ? skillLoader.getMetadata("requirement-analysis").getMaxRounds() : 0;
        int effectiveDecomp = Math.max(decompRounds, 1);
        workflow.addNode(decompositionAgent.name(),
                node_async(new MultiRoundAgentNode(decompModel, decompInstruction,
                        effectiveDecomp, RdWorkflowKeys.DECOMPOSITION_RESULT, eventBus)));

        // 并行推理 Agent
        int archRounds = skillLoader.getMetadata("architecture-design") != null
                ? skillLoader.getMetadata("architecture-design").getMaxRounds() : 0;
        int apiRounds = skillLoader.getMetadata("api-contract-design") != null
                ? skillLoader.getMetadata("api-contract-design").getMaxRounds() : 0;
        for (int i = 0; i < reasoningAgents.size(); i++) {
            ReactAgent agent = reasoningAgents.get(i);
            int rounds = (i == 0) ? archRounds : apiRounds;
            String instruction = (i == 0) ? archInstruction : apiInstruction;
            String modelName = modelNames.get(i).trim();
            ChatModel cm = getOrFallback(chatModels, modelName);
            int effectiveRounds = Math.max(rounds, 1);
            log.info("Skill [{}] MultiRoundAgentNode: {} rounds, model={}",
                    (i == 0) ? "architecture-design" : "api-contract-design", effectiveRounds, modelName);
            workflow.addNode(agent.name(),
                    node_async(new MultiRoundAgentNode(cm, instruction,
                            effectiveRounds, toAgentKey(modelName) + "_result", eventBus)));
        }

        // ==== 主流程（截断到人工审核）====
        workflow.addEdge(StateGraph.START, "workflow_init");
        workflow.addEdge("workflow_init", decompositionAgent.name());

        // 拆解门控：非研发需求直接终止
        workflow.addConditionalEdges(
                decompositionAgent.name(),
                edge_async(state -> {
                    String result = state.value(RdWorkflowKeys.DECOMPOSITION_RESULT, "").toString();
                    return RdWorkflowKeys.isNotDevReq(result) ? "not_dev_req" : "proceed";
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
        workflow.addEdge("merge_parallel_results", MANUAL_REVIEW_NODE);

        // 人工审核条件分支：APPROVED/SENT_BACK/TERMINATED → 全部不进入后续阶段
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
                        "approved", StateGraph.END,
                        "sent_back", decompositionAgent.name(),
                        "terminated", StateGraph.END
                )
        );

        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(rdWorkflowSaver).build())
                .interruptAfter(MANUAL_REVIEW_NODE)
                .interruptBeforeEdge(true)
                .build();

        CompiledGraph compiledGraph = workflow.compile(compileConfig);
        log.info("智能体研发工作流 StateGraph 编译完成（阶段一：人工审核，推理模型数: {}）", getReasoningModelNames().size());
        return compiledGraph;
    }
}
