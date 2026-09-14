package com.agenthub.ai.workflow.config;

import com.agenthub.ai.workflow.node.*;
import com.agenthub.ai.workflow.entity.WorkflowMetadata;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.interceptor.SseStreamingInterceptor;
import com.agenthub.ai.workflow.service.RdWorkflowService;
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
import com.agenthub.ai.workflow.mapper.WorkflowMetadataMapper;
import com.agenthub.ai.workflow.service.DockerSandboxService;
import com.agenthub.ai.workflow.skill.SkillLoader;
import com.agenthub.ai.workflow.tool.CodeRepairTools;
import com.agenthub.ai.workflow.tool.NonDevGuardTools;
import com.agenthub.ai.workflow.tool.SandboxContext;
import com.agenthub.ai.workflow.tool.SandboxTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import com.alibaba.cloud.ai.graph.action.NodeAction;

/**
 * 研发智能体工作流图配置
 * <p>
 * 流程：需求拆解 → 多模型并行推理 → 人工审核(可暂停) → 代码生成 → Harness沙箱验证 → 验证判断 → (失败)代码修复循环
 */
@Slf4j
@Configuration
public class RdWorkflowGraphConfig {

    // ===== 请求期模型选择（前端所选模型，替代已失效的静态角色模型）=====
    // ThreadLocal 在"主链路节点同步执行模型调用"场景可靠；并行 reasoning agents
    // 使用 reasoning-models 列表各自的模型，不受此影响。
    private static final ThreadLocal<String> REQUEST_MODEL = new ThreadLocal<>();

    /** 前端启动工作流时选择的模型名（执行线程内设置，供 DynamicChatModel 动态解析） */
    public static void setRequestModel(String modelName) {
        REQUEST_MODEL.set(modelName);
    }

    public static String getRequestModel() {
        return REQUEST_MODEL.get();
    }

    public static void clearRequestModel() {
        REQUEST_MODEL.remove();
    }

    /** 连续 retry（未写文件）最大次数，防止无限循环 */
    private static final int MAX_RETRY_STREAK = 3;
    /** 连续 retry 计数器（按 threadId，条件边无状态需用静态 Map） */
    private static final Map<String, Integer> retryStreaks = new ConcurrentHashMap<>();
    /** 连续降级通过次数（按 threadId）：连续 2 次降级仍无文件 → 直接失败，避免拖到 maxRepairIterations */
    private static final int MAX_DOWNGRADE_COUNT = 2;
    private static final Map<String, Integer> downgradeCounts = new ConcurrentHashMap<>();

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

    // ===== 模型角色映射（从 YAML 配置驱动，不再硬编码 @Qualifier）=====
    @Value("${agenthub.workflow.model-roles.decomposition:qwen3}")
    private String decompModelName;

    @Value("${agenthub.workflow.model-roles.code-generation:qwen3}")
    private String codeGenModelName;

    @Value("${agenthub.workflow.model-roles.harness:qwen3}")
    private String harnessModelName;

    @Value("${agenthub.workflow.model-roles.repair:qwen3}")
    private String repairModelName;

    @Value("${agenthub.workflow.model-roles.merge-result:qwen3}")
    private String mergeModelName;

    @Value("${agenthub.workflow.model-roles.manual-review:qwen3}")
    private String reviewModelName;

    /**
     * 推理模型名列表（逗号分隔），Spring 原生列表注入对 YAML 列表兼容性更好，
     * 避免 SpEL split 方式在 YAML 列表场景下回退到默认值。
     */
    @Value("${agenthub.workflow.model-roles.reasoning-models:gemma2,qwen3}")
    private String reasoningModelNamesStr;

    @Value("${agenthub.workflow.max-repair-iterations:5}")
    private int maxRepairIterations;

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

    /** StateSerializer，MySQL 和 Redis 模式共用（Jackson JSON 序列化，避免 NotSerializableException） */
    @Bean
    public StateSerializer stateSerializer() {
        AgentStateFactory<OverAllState> stateFactory = OverAllState::new;
        return new SpringAIJacksonStateSerializer(stateFactory);
    }

    /** RedissonClient，仅 Redis 模式时创建 */
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

    // ===== 辅助方法：从动态 Map 中获取模型，找不到则回退 =====
    private ChatModel wrapDynamic(Map<String, ChatModel> chatModels, String roleModelName, OllamaApi ollamaApi) {
        return new DynamicChatModel(chatModels, roleModelName, ollamaApi);
    }

    /**
     * 从 Map 中获取指定模型名的 ChatModel。
     * LLMConfig 注册 ChatModel 时 Bean 名为 "{name}ChatModel"（如 gemma2ChatModel），
     * 因此需要追加 "ChatModel" 后缀进行查找。
     */
    private ChatModel getOrFallback(Map<String, ChatModel> chatModels, String modelName) {
        String beanName = modelName + "ChatModel";
        ChatModel model = chatModels.get(beanName);
        if (model != null) {
            return model;
        }
        log.warn("模型 '{}'（Bean: {}）未在上下文中找到，回退到主模型", modelName, beanName);
        model = chatModels.values().stream().findFirst().orElse(null);
        if (model == null) {
            throw new IllegalStateException("没有可用的 ChatModel Bean，请检查 spring.ai.models 配置");
        }
        return model;
    }

    private static String toAgentKey(String modelName) {
        return "reasoning_" + modelName.trim().replace("-", "_").replace(".", "_");
    }

    @Bean
    public CompiledGraph rdWorkflowCompiledGraph(
            Map<String, ChatModel> chatModels,
            SandboxTools sandboxTools,
            CodeRepairTools codeRepairTools,
            NonDevGuardTools nonDevGuardTools,
            BaseCheckpointSaver rdWorkflowSaver,
            SkillLoader skillLoader,
            WorkflowEventBus eventBus,
            DockerSandboxService sandboxService,
            WorkflowMetadataMapper metadataMapper,
            OllamaApi sharedOllamaApi,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            GitProjectService gitService) throws GraphStateException {
        ChatModel decompModel = wrapDynamic(chatModels, decompModelName, sharedOllamaApi);
        ChatModel codeGenModel = wrapDynamic(chatModels, codeGenModelName, sharedOllamaApi);
        ChatModel harnessModel = wrapDynamic(chatModels, harnessModelName, sharedOllamaApi);
        ChatModel repairModel = wrapDynamic(chatModels, repairModelName, sharedOllamaApi);
        ChatModel mergeModel = wrapDynamic(chatModels, mergeModelName, sharedOllamaApi);
        ChatModel reviewModel = wrapDynamic(chatModels, reviewModelName, sharedOllamaApi);
        log.info("模型分配: decomp={}, codeGen={}, harness={}, repair={}, merge={}, review={}",
                decompModel.getClass().getSimpleName(),
                codeGenModel.getClass().getSimpleName(),
                harnessModel.getClass().getSimpleName(),
                repairModel.getClass().getSimpleName(),
                mergeModel.getClass().getSimpleName(),
                reviewModel.getClass().getSimpleName());

        // 时间戳更新节点（复用），异步更新 workflow_metadata.update_time
        NodeAction updateTimestamp = state -> {
            String threadId = RdWorkflowKeys.extractStateText(state, MultiRoundAgentNode.THREAD_ID_KEY, "");
            try {
                WorkflowMetadata meta = metadataMapper.selectById(threadId);
                if (meta != null) {
                    meta.setUpdateTime(new Date());
                    metadataMapper.updateById(meta);
                }
            } catch (Exception e) {
                log.warn("更新时间戳失败: threadId={}", threadId, e);
            }
            return java.util.Map.of();
        };

        // 1. 需求拆解智能体（加载 skill: requirement-analysis.md）
        String decompositionInstruction = skillLoader.getInstruction("requirement-analysis");
        if (decompositionInstruction.isBlank()) {
            decompositionInstruction = """
                    你是需求拆解智能体。将以下研发需求拆解为可执行的子任务列表。
                    输出格式：子任务编号、描述、优先级、建议使用的模型类型。

                    研发需求：{requirement}

                    审核反馈（如不为空则必须纳入）：{review_feedback}
                    """;
        }
        ReactAgent decompositionAgent = ReactAgent.builder()
                .name("requirement_decomposition")
                .model(decompModel)
                .instruction(decompositionInstruction)
                .methodTools(nonDevGuardTools)
                .streamingInterceptors(new SseStreamingInterceptor(eventBus, RdWorkflowKeys.DECOMPOSITION_RESULT,
                        Integer.MAX_VALUE, Integer.MAX_VALUE))
                .outputKey(RdWorkflowKeys.DECOMPOSITION_RESULT)
                .enableLogging(true)
                .build();

        // 2. 多模型并行推理子智能体（每个模型加载不同的互补 skill，真正差异化并行）
        //    - 模型 0：架构设计（技术栈 + 模块 + 数据模型）
        //    - 模型 1：API 契约设计（接口 + 业务流程 + 跨模块交互）
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
            ChatModel cm = wrapDynamic(chatModels, modelName, sharedOllamaApi);
            String key = toAgentKey(modelName);
            // 第一个推理模型做架构设计，第二个做 API 契约设计
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

        // 3. 正式代码生成智能体（加载 skill: code-generation.md）
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

        // 4. HarnessAgent + DinD 沙箱工具（加载 skill: sandbox-testing.md）
        String harnessInstruction = skillLoader.getInstruction("sandbox-testing");
        if (harnessInstruction.isBlank()) {
            harnessInstruction = """
                    你是 HarnessAgent，负责在 DinD 安全沙箱中调度代码编译、运行和测试。
                    必须依次调用 compileCode、executeInSandbox、runTests 三个工具完成验证，
                    并汇总所有工具返回结果为一段完整报告。

                    待验证代码：
                    {generated_code}
                    """;
        }
        ReactAgent harnessAgent = ReactAgent.builder()
                .name("harness_execution")
                .model(harnessModel)
                .instruction(harnessInstruction)
                .outputKey(RdWorkflowKeys.HARNESS_RESULT)
                .methodTools(sandboxTools)
                .streamingInterceptors(new SseStreamingInterceptor(eventBus, RdWorkflowKeys.HARNESS_RESULT))
                .enableLogging(true)
                .build();

        // 5. 代码修复智能体（加载 skill: code-repair.md）
        String repairInstruction = skillLoader.getInstruction("code-repair");
        if (!repairInstruction.isBlank()) {
            repairInstruction = repairInstruction.replace("{max_repair_iterations}",
                    String.valueOf(maxRepairIterations));
        }
        if (repairInstruction.isBlank()) {
            repairInstruction = """
                    你是代码修复智能体。根据 Harness 测试失败信息迭代优化代码。

                    当前代码：{generated_code}
                    执行结果：{harness_result}

                    输出修复后的完整 Java 代码，确保可通过编译和测试。
                    """;
        }
        ReactAgent codeRepairAgent = ReactAgent.builder()
                .name("code_repair")
                .model(repairModel)
                .instruction(repairInstruction)
                .methodTools(codeRepairTools)
                .outputKey(RdWorkflowKeys.CODE_REPAIR_ANALYSIS)
                .streamingInterceptors(new SseStreamingInterceptor(eventBus, RdWorkflowKeys.CODE_REPAIR_ANALYSIS,
                        Integer.MAX_VALUE, Integer.MAX_VALUE))
                .enableLogging(true)
                .build();

        KeyStrategyFactory keyStrategyFactory = () -> {
            HashMap<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put(RdWorkflowKeys.REQUIREMENT, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.DECOMPOSITION_RESULT, new ReplaceStrategy());
            // _thread_id_ 用于 MultiRoundAgentNode 推送 SSE 事件，必须在节点间保留
            strategies.put(MultiRoundAgentNode.THREAD_ID_KEY, new ReplaceStrategy());
            // 动态注册每个推理 Agent 的 outputKey
            for (String modelName : getReasoningModelNames()) {
                strategies.put(toAgentKey(modelName) + "_result", new ReplaceStrategy());
            }
            strategies.put(RdWorkflowKeys.PARALLEL_REASONING_RESULT, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.REVIEW_CONTENT, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.REVIEW_DECISION, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.GENERATED_CODE, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.HARNESS_RESULT, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.VALIDATION_PASSED, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.REPAIR_COUNT, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.WORKFLOW_STATUS, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.WORKFLOW_MESSAGE, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.HARNESS_ERROR_DETAIL, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.REVIEW_FEEDBACK, new ReplaceStrategy());
            strategies.put(RdWorkflowKeys.REPAIR_FEEDBACK, new ReplaceStrategy());
            strategies.put(CodeProjectWriteNode.CODE_PROJECT_ROOT, new ReplaceStrategy());
            return strategies;
        };

        StateGraph workflow = new StateGraph(keyStrategyFactory);

        // 普通节点
        workflow.addNode("workflow_init", node_async(new WorkflowInitNode(eventBus)));
        workflow.addNode(MANUAL_REVIEW_NODE, node_async(new ManualReviewNode(eventBus, reviewModel)));
        workflow.addNode("merge_parallel_results", node_async(new ParallelResultMergeNode(getReasoningModelNames(), mergeModel, eventBus)));
        workflow.addNode("validation", node_async(new ValidationNode(eventBus)));
        workflow.addNode("increment_repair", node_async(new RepairCountIncrementNode(eventBus, maxRepairIterations)));
        workflow.addNode("code_project_write", node_async(new CodeProjectWriteNode(eventBus, gitService)));
        workflow.addNode("sandbox_init", node_async(new SandboxInitNode(sandboxService, eventBus, gitService)));
        workflow.addNode("sandbox_cleanup", node_async(new SandboxCleanupNode(sandboxService)));

        // Agent 节点（对 maxRounds > 1 的 skill 使用 MultiRoundAgentNode 实现逐轮循环推进）
        int decompRounds = skillLoader.getMetadata("requirement-analysis") != null
                ? skillLoader.getMetadata("requirement-analysis").getMaxRounds() : 0;
        if (decompRounds > 1) {
            log.info("Skill [requirement-analysis] 使用多轮循环模式: {} rounds", decompRounds);
            workflow.addNode(decompositionAgent.name(),
                    node_async(new MultiRoundAgentNode(decompModel, decompositionInstruction,
                            decompRounds, RdWorkflowKeys.DECOMPOSITION_RESULT, eventBus)));
        } else {
            workflow.addNode(decompositionAgent.name(), decompositionAgent.asNode(true, false));
        }

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
            ChatModel cm = wrapDynamic(chatModels, modelName, sharedOllamaApi);
            // 统一走 MultiRoundAgentNode，保证输出为 String 而非 GraphResponse 对象
            int effectiveRounds = Math.max(rounds, 1);
            log.info("Skill [{}] 使用 MultiRoundAgentNode: {} rounds, model={}",
                    skillName, effectiveRounds, modelName);
            workflow.addNode(agent.name(),
                    node_async(new MultiRoundAgentNode(cm, instruction,
                            effectiveRounds, toAgentKey(modelName) + "_result", eventBus)));
        }

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

        // harnessAgent 携带沙箱工具，由 SandboxInitNode + SandboxCleanupNode 管理生命周期
        workflow.addNode(harnessAgent.name(), harnessAgent.asNode(true, false));
        // codeRepairAgent 已有 graph-level 循环，不使用 MultiRoundAgentNode
        workflow.addNode(codeRepairAgent.name(), codeRepairAgent.asNode(true, true));

        // 线性主流程
        workflow.addEdge(StateGraph.START, "workflow_init");
        workflow.addEdge("workflow_init", decompositionAgent.name());

        // 归一化：ReactAgent.asNode 输出 AssistantMessage，MultiRoundAgentNode 输出 String
        // 统一转为 String 存入 state，避免下游 ClassCastException
        workflow.addNode("decomp_normalize", node_async(state -> {
            Object v = state.value(RdWorkflowKeys.DECOMPOSITION_RESULT).orElse("");
            String text = v instanceof org.springframework.ai.chat.messages.AssistantMessage msg
                    ? msg.getText() : v.toString();
            return Map.of(RdWorkflowKeys.DECOMPOSITION_RESULT, text);
        }));
        workflow.addEdge(decompositionAgent.name(), "decomp_normalize");

        // 拆解结果门控：非研发需求直接终止，研发需求继续并行推理
        workflow.addNode("decomposition_gate", node_async(new DecompositionGateNode(eventBus)));
        // 🕐 需求拆解完成 → 更新时间
        workflow.addNode("ts_decomp_done", node_async(updateTimestamp));
        workflow.addEdge("decomp_normalize", "ts_decomp_done");
        workflow.addConditionalEdges(
                "ts_decomp_done",
                edge_async(state -> {
                    String result = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.DECOMPOSITION_RESULT, "");
                    String threadId = RdWorkflowKeys.extractStateText(state, MultiRoundAgentNode.THREAD_ID_KEY, "");
                    // 文本标记：NOT_DEV_REQ 拒绝
                    if (RdWorkflowKeys.isNotDevReq(result)) {
                        return "not_dev_req";
                    }
                    // 工具调用检测：Agent 调了 reportAssessment(false) 才拦截
                    // null = 工具未调用（如 MultiRoundAgentNode 不支持工具），不拦截，走文本标记兜底
                    Boolean isDev = NonDevGuardTools.getAndClear(threadId);
                    if (Boolean.FALSE.equals(isDev)) {
                        log.warn("非研发需求拦截: threadId={}", threadId);
                        return "not_dev_req";
                    }
                    return "proceed";
                }),
                Map.of(
                        "not_dev_req", StateGraph.END,
                        "proceed", "decomposition_gate"
                )
        );

        // 多模型并行推理：同一源节点扇出到多个 Agent，框架自动并行执行
        for (ReactAgent agent : reasoningAgents) {
            workflow.addEdge("decomposition_gate", agent.name());
        }
        for (ReactAgent agent : reasoningAgents) {
            workflow.addEdge(agent.name(), "merge_parallel_results");
        }
        workflow.addEdge("merge_parallel_results", "ts_reasoning_done");
        workflow.addNode("ts_reasoning_done", node_async(updateTimestamp));
        workflow.addEdge("ts_reasoning_done", MANUAL_REVIEW_NODE);

        // 人工审核条件分支：通过 / 驳回 / 拒绝
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

        workflow.addEdge(codeGenerationAgent.name(), "ts_code_done");
        workflow.addNode("ts_code_done", node_async(updateTimestamp));
        workflow.addEdge("ts_code_done", "code_project_write");
        workflow.addEdge("code_project_write", "sandbox_init");
        workflow.addEdge("sandbox_init", harnessAgent.name());
        workflow.addEdge(harnessAgent.name(), "sandbox_cleanup");
        workflow.addEdge("sandbox_cleanup", "ts_sandbox_done");
        workflow.addNode("ts_sandbox_done", node_async(updateTimestamp));
        workflow.addEdge("ts_sandbox_done", "validation");

        // 🕐 验证判断完成 → 更新时间
        workflow.addNode("ts_validation_done", node_async(updateTimestamp));
        workflow.addEdge("validation", "ts_validation_done");

        // 验证条件分支：通过 / 修复循环 / 超过最大修复次数
        workflow.addConditionalEdges(
                "ts_validation_done",
                edge_async(state -> {
                    boolean passed = Boolean.parseBoolean(
                            state.value(RdWorkflowKeys.VALIDATION_PASSED).map(Object::toString).orElse("false"));
                    if (passed) {
                        return "passed";
                    }
                    int repairCount = Integer.parseInt(
                            state.value(RdWorkflowKeys.REPAIR_COUNT, 0).toString());
                    if (repairCount >= maxRepairIterations) {
                        return "max_repair";
                    }
                    return "failed";
                }),
                Map.of(
                        "passed", StateGraph.END,
                        "failed", "increment_repair",
                        "max_repair", StateGraph.END
                )
        );

        // 代码修复循环：先清空旧结果 → 修复 → 校验写出 → 沙箱验证
        workflow.addEdge("increment_repair", codeRepairAgent.name());

        workflow.addConditionalEdges(
                codeRepairAgent.name(),
                edge_async(state -> {
                    int count = Integer.parseInt(
                            state.value(RdWorkflowKeys.REPAIR_COUNT, 0).toString());
                    if (count >= maxRepairIterations) {
                        return "max_repair";
                    }
                    // 从全局 Map 获取修复目录（解决 ThreadLocal 跨线程失效）
                    String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY)
                            .map(Object::toString).orElse(null);
                    String repairRoot = SandboxContext.getRepairProjectRootForThread(threadId);

                    // 检查目录下是否有实际源文件（.java/.xml/.properties/.yml/.py 等）
                    // 必须同时满足：目录有源文件 + 本轮 Agent 实际调用了写入工具。
                    // 修复目录是初始代码的完整副本，仅"目录有文件"不能证明修复生效，
                    // 否则模型只输出文本（不调工具）也会被误判为修复完成。
                    boolean hasFiles = repairRoot != null && !repairRoot.isBlank()
                            && hasSourceFiles(repairRoot)
                            && com.agenthub.ai.workflow.tool.CodeRepairTools.hasWrittenThisRound(threadId);

                    if (hasFiles) {
                        retryStreaks.remove(threadId);
                        downgradeCounts.remove(threadId);
                        eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE,
                                "代码修复完成，进入沙箱验证...", "RUNNING");
                        return "ok";
                    }
                    // 没文件 → 直接回到 codeRepair（不经过 increment_repair，避免重复递增计数）
                    // repair_feedback 已在上一轮 increment_repair 中设置，codeRepairAgent 通过 {repair_feedback} 获取
                    int streak = retryStreaks.merge(threadId, 1, Integer::sum);
                    String msg = "代码修复中（第 " + streak + " 轮），请等待...";
                    eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, msg, "RUNNING");
                    log.warn("修复 Agent 第 {} 轮未写入任何文件 (repairRoot={}), retryStreak={}, 直接返回 codeRepair",
                            count, repairRoot, streak);
                    if (streak >= MAX_RETRY_STREAK) {
                        // 降级：Agent 有修改意图但没文件 → 代码可能正确，降级通过进入正常修复循环
                        String repairOutput = state.value(RdWorkflowKeys.CODE_REPAIR_ANALYSIS)
                                .map(Object::toString).orElse("");
                        if (hasRepairIntent(repairOutput)) {
                            // 降级前兜底：模型未调工具但文本含完整代码 → 直接从文本提取写盘
                            int written = com.agenthub.ai.workflow.tool.CodeRepairTools
                                    .writeFilesFromText(threadId, repairOutput);
                            if (written > 0) {
                                retryStreaks.remove(threadId);
                                downgradeCounts.remove(threadId);
                                log.info("修复 Agent 文本提取写盘 {} 个文件，进入验证 (threadId={})", written, threadId);
                                // 明确告知模型本轮未调用工具，下轮必须调用文件工具
                                eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE,
                                        "⚠️ 本轮你未调用文件工具，系统已从你的文本输出中提取代码写入修复目录。"
                                                + "下一轮请务必直接调用 readCodeFile / patchCodeFile / writeCodeFile 工具修复，不要只输出文本。",
                                        "RUNNING");
                                return "ok";
                            }
                            // 连续降级计数：若连续多次降级仍未产出文件 → 模型 tool calling 能力不足，直接失败
                            int downgrade = downgradeCounts.merge(threadId, 1, Integer::sum);
                            if (downgrade >= MAX_DOWNGRADE_COUNT) {
                                retryStreaks.remove(threadId);
                                downgradeCounts.remove(threadId);
                                String reason = "修复 Agent 连续 " + MAX_DOWNGRADE_COUNT
                                        + " 轮降级通过但仍未写入任何文件，疑似模型工具调用能力不足"
                                        + "（当前修复模型未正确调用 readCodeFile/patchCodeFile/writeCodeFile）。"
                                        + "建议更换工具调用更稳定的模型后恢复执行。";
                                log.warn("修复 Agent 连续降级 {} 次仍无文件，直接失败: threadId={}, 原因={}",
                                        downgrade, threadId, reason);
                                RdWorkflowService.errorStatusDetails.put(threadId, "FAILED — " + reason);
                                eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE,
                                        "代码修复失败：" + reason, "RUNNING");
                                eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS,
                                        "FAILED — 模型工具调用异常", "FAILED");
                                return "max_repair";
                            }
                            log.info("修复 Agent 无文件但有修改意图 (streak={}, downgrade={}/{})，降级通过",
                                    streak, downgrade, MAX_DOWNGRADE_COUNT);
                            retryStreaks.remove(threadId);
                            return "ok";
                        }
                        retryStreaks.remove(threadId);
                        downgradeCounts.remove(threadId);
                        eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE,
                                "代码修复失败：已重试 " + streak + " 次，仍未产出有效文件", "RUNNING");
                        eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS,
                                "FAILED — 修复耗尽，未产出文件", "FAILED");
                        return "max_repair";
                    }
                    return "retry";
                }),
                Map.of(
                        "ok", "sandbox_init",
                        "retry", "retry_feedback",
                        "max_repair", "repair_exhausted"
                )
        );
        // 注入修复反馈：retryStreaks 绕过 increment_repair，Agent 每次看到相同 prompt，需独立注入
        workflow.addNode("retry_feedback", node_async(state -> {
            String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY)
                    .map(Object::toString).orElse("");
            int streak = retryStreaks.getOrDefault(threadId, 0);
            String feedback = "⚠️ 上一轮修复（第" + streak + "次）你没有写入任何文件！"
                    + "本轮必须通过 readCodeFile（读取已有文件真实内容）→ patchCodeFile（定点修改已有文件）"
                    + "或 writeCodeFile（新建缺失文件）写入修复文件，再输出文本报告。"
                    + "不写入文件 = 本轮完全无效，系统会继续重试。";
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE,
                    "第" + streak + "次修复未写文件，正在重试...", "RUNNING");
            return Map.of(RdWorkflowKeys.REPAIR_FEEDBACK, feedback);
        }));
        workflow.addEdge("retry_feedback", codeRepairAgent.name());
        // 修复耗尽节点：写入 FAILED 状态（errorStatusDetails 在 pushFinalState 中跨线程可靠读取）
        workflow.addNode("repair_exhausted", node_async(state -> {
            String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY)
                    .map(Object::toString).orElse("");
            // 若降级失败已写入更具体的原因（如"模型工具调用异常"），则不覆盖
            if (!RdWorkflowService.errorStatusDetails.containsKey(threadId)) {
                RdWorkflowService.errorStatusDetails.put(threadId, "FAILED — 修复耗尽，未产出有效文件");
            }
            String detail = RdWorkflowService.errorStatusDetails.getOrDefault(threadId, "修复循环耗尽");
            log.info("[REPAIR-EXHAUSTED] detail={}, threadId={}", detail, threadId);
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS,
                    detail.startsWith("FAILED") ? detail : "FAILED — " + detail, "FAILED");
            return Map.of(
                    RdWorkflowKeys.WORKFLOW_MESSAGE, "代码修复失败：" + detail.replaceFirst("^FAILED — ", "")
            );
        }));
        workflow.addEdge("repair_exhausted", StateGraph.END);

        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(rdWorkflowSaver).build())
                .interruptAfter(MANUAL_REVIEW_NODE)
                .interruptBeforeEdge(true)
                .build();

        CompiledGraph compiledGraph = workflow.compile(compileConfig);
        log.info("研发工作流 StateGraph 编译完成（推理模型数: {}）", getReasoningModelNames().size());
        return compiledGraph;
    }

    /**
     * 检查修复目录下是否有实际的源代码文件。
     * 支持的扩展名：.java .xml .properties .yml .yaml .py .js .ts .go .rs .c .cpp .h .hpp
     */
    private static boolean hasSourceFiles(String rootPath) {
        try {
            java.nio.file.Path root = java.nio.file.Path.of(rootPath);
            if (!java.nio.file.Files.isDirectory(root)) {
                return false;
            }
            final java.util.Set<String> extensions = java.util.Set.of(
                    ".java", ".xml", ".properties", ".yml", ".yaml",
                    ".py", ".js", ".ts", ".go", ".rs", ".c", ".cpp", ".h", ".hpp"
            );
            try (var stream = java.nio.file.Files.walk(root)) {
                return stream.anyMatch(p -> java.nio.file.Files.isRegularFile(p)
                        && extensions.stream().anyMatch(ext -> p.toString().endsWith(ext)));
            }
        } catch (Exception e) {
            log.warn("检查修复目录文件失败: root={}, error={}", rootPath, e.getMessage());
            return false;
        }
    }

    /**
     * 检测修复 Agent 文本输出中是否包含修改意图（代码块或文件路径）。
     * 用于降级判定：有意图但 tool call 失败时降级通过，避免误判 FAILED。
     */
    private static boolean hasRepairIntent(String output) {
        if (output == null || output.isBlank()) return false;
        // 代码块标记：```java ```python ```xml ```json 等
        if (output.contains("```")) return true;
        // 常见源码文件路径模式
        if (output.matches("(?s).*\\b(pom\\.xml|build\\.gradle|src/|package\\.json|Dockerfile|\\.java|\\.py|\\.js|\\.ts|\\.xml|\\.yml|\\.yaml|\\.properties)\\b.*")) return true;
        return false;
    }
}
