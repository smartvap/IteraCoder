package com.agenthub.ai.workflow.service;

import com.agenthub.ai.workflow.node.MultiRoundAgentNode;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import com.agenthub.ai.workflow.config.RdWorkflowGraphConfig;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.constant.RdWorkflowReviewDecision;
import com.agenthub.ai.workflow.constant.RdWorkflowStatus;
import com.agenthub.ai.workflow.node.CodeProjectWriteNode;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.interceptor.SseStreamingInterceptor;
import com.agenthub.ai.workflow.mapper.WorkflowMetadataMapper;
import com.agenthub.ai.workflow.entity.WorkflowMetadata;
import com.agenthub.ai.workflow.tool.SandboxContext;
import com.agenthub.ai.workflow.tool.CodeProjectWriter;
import com.agenthub.ai.workflow.tool.CodeRepairTools;
import com.agenthub.ai.workflow.skill.SkillLoader;
import com.agenthub.ai.workflow.vo.RdWorkflowResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import reactor.core.Disposable;

/**
 * 研发工作流服务：启动、人工审核恢复、状态查询。
 * <p>
 * 工作流执行在当前线程中通过 stream() + blockLast() 驱动，
 * 为防止阻塞 HTTP 线程，将实际执行提交到独立的线程池中。
 */
@Slf4j
@Service
public class RdWorkflowService {

    /** doOnError/pushErrorToSse 推送的详细错误状态，供 pushFinalState 读取避免被简单状态覆盖 */
    public static final Map<String, String> errorStatusDetails = new ConcurrentHashMap<>();

    /** 活跃的工作流 reactive 流，用于手动终止 */
    private final Map<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    private final CompiledGraph rdWorkflowCompiledGraph;
    private final WorkflowEventBus eventBus;
    private final WorkflowMetadataMapper metadataMapper;
    private final DockerSandboxService sandboxService;
    private final SkillLoader skillLoader;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Value("${agenthub.workflow.max-repair-iterations:5}")
    private int maxRepairIterations;

    @Value("${agenthub.workflow.saver-type:memory}")
    private String saverType;
	
    private final com.alibaba.cloud.ai.graph.serializer.StateSerializer stateSerializer;
    private final GitProjectService gitService;
    private final com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver rdWorkflowSaver;

    @Value("${agenthub.workflow.code-storage-path:}")
    private String codeStoragePath;

    /** 工作流整体执行超时（分钟），防止 CPU 慢推理时线程永久阻塞 */
    @Value("${agenthub.workflow.execution-timeout-minutes:60}")
    private long workflowTimeoutMinutes;

    public RdWorkflowService(CompiledGraph rdWorkflowCompiledGraph, WorkflowEventBus eventBus,
            WorkflowMetadataMapper metadataMapper, DockerSandboxService sandboxService,
            SkillLoader skillLoader,
            com.alibaba.cloud.ai.graph.serializer.StateSerializer stateSerializer,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            GitProjectService gitService,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver rdWorkflowSaver) {
        this.rdWorkflowCompiledGraph = rdWorkflowCompiledGraph;
        this.eventBus = eventBus;
        this.metadataMapper = metadataMapper;
        this.sandboxService = sandboxService;
        this.skillLoader = skillLoader;
        this.stateSerializer = stateSerializer;
        this.gitService = gitService;
        this.rdWorkflowSaver = rdWorkflowSaver;
    }



    /**
     * 启动时清理遗留的 RUNNING 状态记录（服务重启前异常中断的流程）。
     * <ul>
     *   <li>memory saver：checkpoint 已随 JVM 丢失 → 标记 TERMINATED</li>
     *   <li>mysql/redis saver：checkpoint 持久化保留 → 保持 RUNNING，用户可在页面手动终止或恢复</li>
     * </ul>
     */
    @PostConstruct
    public void cleanStaleRunning() {
        if ("memory".equals(saverType)) {
            try {
                var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowMetadata>()
                        .eq(WorkflowMetadata::getStatus, "RUNNING");
                var stale = metadataMapper.selectList(wrapper);
                for (WorkflowMetadata meta : stale) {
                    String threadId = meta.getThreadId();
                    meta.setStatus("TERMINATED");
                    meta.setRemark("系统自动终止");
                    updateMetadataStatus(meta);
                    try {
                        RunnableConfig config = buildConfig(threadId);
                        Map<String, Object> stateUpdate = new HashMap<>();
                        stateUpdate.put(RdWorkflowKeys.WORKFLOW_STATUS, "TERMINATED");
                        stateUpdate.put(RdWorkflowKeys.WORKFLOW_MESSAGE, "系统自动终止");
                        rdWorkflowCompiledGraph.updateState(config, stateUpdate, RdWorkflowGraphConfig.MANUAL_REVIEW_NODE);
                    } catch (Exception e) {
                        log.warn("清理 checkpoint 失败（可能已丢失）: threadId={}", threadId, e);
                    }
                }
                if (!stale.isEmpty()) {
                    log.info("启动清理(memory)：{} 条 RUNNING 记录已标记为 TERMINATED", stale.size());
                }
            } catch (Exception e) {
                log.warn("启动清理遗留 RUNNING 记录失败", e);
            }
        } else {
            log.info("saver-type={}，跳过自动终止，RUNNING 记录保留（用户可从页面手动操作）", saverType);
        }
    }

    /**
     * 手动终止工作流：更新 metadata、checkpoint，切断流，推送最终状态到 SSE。
     */
    public void terminateWorkflow(String threadId) {
        WorkflowMetadata meta = metadataMapper.selectById(threadId);
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "流程不存在");
        }
        // 1. 更新元数据表
        meta.setStatus("TERMINATED");
        meta.setRemark("手动终止");
        updateMetadataStatus(meta);

        // 2. 更新 graph checkpoint（仅写 TERMINATED 状态，不写 REVIEW_DECISION，
        //    避免前端误判"等待人工审核"已完成）
        RunnableConfig config = buildConfig(threadId);
        try {
            Map<String, Object> stateUpdate = new HashMap<>();
            stateUpdate.put(RdWorkflowKeys.WORKFLOW_STATUS, "TERMINATED");
            stateUpdate.put(RdWorkflowKeys.WORKFLOW_MESSAGE, "手动终止");
            rdWorkflowCompiledGraph.updateState(config, stateUpdate, RdWorkflowGraphConfig.MANUAL_REVIEW_NODE);
            log.info("checkpoint 已更新为 TERMINATED: threadId={}", threadId);
        } catch (Exception e) {
            log.warn("更新 checkpoint 失败（流程可能尚未创建 checkpoint）: threadId={}", threadId, e);
        }

        // 3. 切断运行中的 reactive 流
        Disposable disposable = activeStreams.remove(threadId);
        boolean wasActive = (disposable != null);
        if (wasActive && !disposable.isDisposed()) {
            disposable.dispose();
            log.info("已切断 reactive 流: threadId={}", threadId);
        }

        // 4. 推送最终状态到 SSE（dispose 不会触发 onComplete，需手动补调）
        if (wasActive) {
            try {
                pushFinalState(config, null);
            } catch (Exception e) {
                log.warn("推送终止状态失败: threadId={}", threadId, e);
            }
        }

        log.info("手动终止工作流完成: threadId={}", threadId);
    }

    /**
     * 构建 RunnableConfig。
     * threadId 通过 SseStreamingInterceptor.registerThreadId() 注册到全局注册表，
     * 不再依赖 RunnableConfig.context()（Builder 未暴露 context 设置方法）。
     */
    private RunnableConfig buildConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    /**
     * 为所有使用 SseStreamingInterceptor 的 Agent 注册当前 threadId。
     * 使拦截器在 ReactAgent 内部工具调用循环中能获取到 threadId，
     * 从而将流式输出推送到正确的 SSE 通道。
     */
    private void registerSseInterceptors(String threadId) {
        SseStreamingInterceptor.registerThreadId(RdWorkflowKeys.DECOMPOSITION_RESULT, threadId);
        SseStreamingInterceptor.registerThreadId(RdWorkflowKeys.HARNESS_RESULT, threadId);
        SseStreamingInterceptor.registerThreadId(RdWorkflowKeys.GENERATED_CODE, threadId);
        SseStreamingInterceptor.registerThreadId(RdWorkflowKeys.CODE_REPAIR_ANALYSIS, threadId);
    }

    /**
     * 启动研发工作流（异步）。
     * <p>
     * 实际工作流执行在独立线程中进行，HTTP 请求立即返回 threadId，
     * 前端通过轮询 /state/{threadId} 获取实时进度。
     */
    public RdWorkflowResultVO start(String requirement, String modelName) {
        if (requirement == null || requirement.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "研发需求不能为空");
        }
        String threadId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> input = new HashMap<>();
        input.put(RdWorkflowKeys.REQUIREMENT, requirement.trim());
        input.put(MultiRoundAgentNode.THREAD_ID_KEY, threadId);
        input.put(RdWorkflowKeys.REPAIR_COUNT, 0);
        RunnableConfig config = buildConfig(threadId);

        // 写入元数据表
        WorkflowMetadata meta = new WorkflowMetadata();
        meta.setThreadId(threadId);
        meta.setRequirement(requirement.trim());
        meta.setReviewFeedback("");
        meta.setStatus("RUNNING");
        meta.setCreateTime(new Date());
        meta.setUpdateTime(new Date());
        metadataMapper.insert(meta);

        // 异步执行工作流，不阻塞 HTTP 线程
        CompletableFuture.runAsync(() -> {
            // ThreadLocal 必须在执行线程内设置，供 DynamicChatModel 读取前端选择的模型
            if (modelName != null && !modelName.isBlank()) {
                RdWorkflowGraphConfig.setRequestModel(modelName.trim());
            }
            try {
                // 注册 threadId 到 SseStreamingInterceptor，使 ReactAgent 的流式输出能推送到 SSE
                registerSseInterceptors(threadId);
                AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
                Disposable disposable = rdWorkflowCompiledGraph.stream(input, config)
                        .subscribe(
                                lastOutput::set,
                                e -> {
                                    activeStreams.remove(threadId);
                                    pushErrorToSse(threadId, e);
                                    log.error("工作流执行异常: threadId={}", threadId, e);
                                    handleWorkflowException(threadId, config, e instanceof Exception ex ? ex : new RuntimeException(e));
                                },
                                () -> {
                                    activeStreams.remove(threadId);
                                    pushFinalState(config, lastOutput.get());
                                    log.info("工作流执行完成: threadId={}", threadId);
                                }
                        );
                activeStreams.put(threadId, disposable);
            } catch (Exception e) {
                activeStreams.remove(threadId);
                pushErrorToSse(threadId, e);
                log.error("工作流初始化异常: threadId={}", threadId, e);
                handleWorkflowException(threadId, config, e);
            } finally {
                RdWorkflowGraphConfig.clearRequestModel();
            }
        }, executor).orTimeout(workflowTimeoutMinutes, java.util.concurrent.TimeUnit.MINUTES)
          .exceptionally(ex -> {
              // 超时或其他异常：标记工作流失败
              log.error("工作流执行超时或异常: threadId={}, timeout={}min", threadId, workflowTimeoutMinutes);
              activeStreams.remove(threadId);
              handleWorkflowException(threadId, config,
                      ex instanceof java.util.concurrent.TimeoutException
                              ? new RuntimeException("工作流执行超时（" + workflowTimeoutMinutes + " 分钟），请检查 LLM 服务状态或使用更小的模型")
                              : new RuntimeException(ex));
              return null;
          });

        log.info("工作流已启动（异步）: threadId={}", threadId);
        return RdWorkflowResultVO.builder()
                .threadId(threadId)
                .status(RdWorkflowStatus.RUNNING)
                .message("工作流已启动，正在执行中...")
                .build();
    }

    /**
     * 人工审核后恢复工作流（异步）。
     * <p>
     * TERMINATED 决策同步返回（无需后续执行），APPROVED/SENT_BACK 异步执行，
     * 前端通过轮询获取实时进度。
     */
    public RdWorkflowResultVO resume(String threadId, RdWorkflowReviewDecision decision, String comment, String modelName) {
        if (threadId == null || threadId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "threadId 不能为空");
        }
        if (decision == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "审核决策不能为空");
        }

        RunnableConfig config = buildConfig(threadId);

        try {
            // 0. 前置检查：终态工作流不允许恢复
            // 确定性读取最新 checkpoint（绕过框架 MysqlSaver 的同秒排序不稳定问题）
            Map<String, Object> resumeStateData = queryLatestState(threadId);
            OverAllState resumeState = (resumeStateData != null)
                    ? stateSerializer.stateOf(resumeStateData) : null;
            if (resumeState != null) {
                String existingDecision = resumeState
                        .value(RdWorkflowKeys.REVIEW_DECISION, "").toString();
                if ("TERMINATED".equals(existingDecision)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "工作流已终止，无法再次操作");
                }
                String status = resumeState
                        .value(RdWorkflowKeys.WORKFLOW_STATUS, "").toString();
                if ("COMPLETED".equals(status)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "工作流已完成，无法再次操作");
                }
            }

            Map<String, Object> stateUpdate = new HashMap<>();
            stateUpdate.put(RdWorkflowKeys.REVIEW_DECISION, decision.name());
            stateUpdate.put(RdWorkflowKeys.REVIEW_FEEDBACK,
                    (comment != null && !comment.isBlank()) ? comment : "");
            // 确保 _thread_id_ 存在于 State 中，供 MultiRoundAgentNode 推送 SSE 事件
            stateUpdate.put(MultiRoundAgentNode.THREAD_ID_KEY, threadId);

            // TERMINATED / APPROVED / SENT_BACK 分别设置不同的 workflow_status
            if (RdWorkflowReviewDecision.TERMINATED.equals(decision)) {
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_STATUS, "TERMINATED");
                stateUpdate.put("_remark", "人工审核拒绝");
            } else {
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
                // 清除旧的暂停消息，避免残留误导前端
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_MESSAGE, "");
            }

            // 更新元数据表：审核备注 + 状态
            WorkflowMetadata meta = new WorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setReviewFeedback(comment != null ? comment : "");
            meta.setStatus(stateUpdate.get(RdWorkflowKeys.WORKFLOW_STATUS).toString());
            if (stateUpdate.containsKey("_remark")) {
                meta.setRemark(stateUpdate.get("_remark").toString());
            }
            updateMetadataStatus(meta);

            // 所有恢复操作都清空 SSE 事件队列中的旧事件，
            // 避免新 SSE 连接消费到上次执行的残留终态事件（如 WAITING_REVIEW）
            eventBus.clearEvents(threadId);

            // SENT_BACK：回到需求拆解重跑，清除全部后续节点的残留旧值
            if (RdWorkflowReviewDecision.SENT_BACK.equals(decision)) {
                // 仅 maxRounds=1（ReactAgent.asNode，不解析 {review_feedback}）时把反馈拼入需求
                int decompRounds = 0;
                var decompMeta = skillLoader.getMetadata("requirement-analysis");
                if (decompMeta != null) decompRounds = decompMeta.getMaxRounds();
                if (decompRounds == 1 && comment != null && !comment.isBlank()) {
                    String origReq = resumeState.value(RdWorkflowKeys.REQUIREMENT)
                            .map(Object::toString).orElse("");
                    stateUpdate.put(RdWorkflowKeys.REQUIREMENT,
                            origReq + "\n\n【审核备注】\n" + comment);
                }
                stateUpdate.put(RdWorkflowKeys.DECOMPOSITION_RESULT, null);
                stateUpdate.put(RdWorkflowKeys.PARALLEL_REASONING_RESULT, null);
                stateUpdate.put(RdWorkflowKeys.REVIEW_CONTENT, null);
                stateUpdate.put(RdWorkflowKeys.GENERATED_CODE, null);
                stateUpdate.put(RdWorkflowKeys.HARNESS_RESULT, null);
                stateUpdate.put(RdWorkflowKeys.VALIDATION_PASSED, null);
                stateUpdate.put(CodeProjectWriteNode.CODE_PROJECT_ROOT, null);
                // 动态清除所有 reasoning_*_result 字段
                for (String key : resumeState.data().keySet()) {
                    if (key.startsWith("reasoning_") && key.endsWith("_result")) {
                        stateUpdate.put(key, null);
                    }
                }
            }

            RunnableConfig updatedConfig = rdWorkflowCompiledGraph.updateState(
                    config, stateUpdate, RdWorkflowGraphConfig.MANUAL_REVIEW_NODE);

            // TERMINATED 同步返回
            if (RdWorkflowReviewDecision.TERMINATED.equals(decision)) {
                String msg = (comment != null && !comment.isBlank()) ? comment : "工作流已终止";
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_MESSAGE, msg);
                // 重新 updateState，把 WORKFLOW_MESSAGE 也写进去
                updatedConfig = rdWorkflowCompiledGraph.updateState(
                        config, stateUpdate, RdWorkflowGraphConfig.MANUAL_REVIEW_NODE);
                // 切断仍在运行中的 reactive 流（RUNNING 阶段点击“结束”时，必须取消后端 AI 对话）
                Disposable activeFlow = activeStreams.remove(threadId);
                if (activeFlow != null && !activeFlow.isDisposed()) {
                    activeFlow.dispose();
                    log.info("TERMINATED 决策已切断运行中的工作流流: threadId={}", threadId);
                    pushFinalState(config, null);
                }
                OverAllState state = rdWorkflowCompiledGraph.getState(updatedConfig).state();
                return RdWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RdWorkflowStatus.TERMINATED)
                        .interrupted(false)
                        .state(sanitizeStateForSerialization(state.data()))
                        .message(msg)
                        .build();
            }

            // APPROVED / SENT_BACK：异步恢复执行
            final RunnableConfig resumeConfig = updatedConfig;
            final String resumeModel = (modelName != null && !modelName.isBlank()) ? modelName.trim() : null;
            CompletableFuture.runAsync(() -> {
                // 执行线程内设置请求模型，供 DynamicChatModel 动态解析（线程绑定，恢复执行后半段使用）
                if (resumeModel != null) {
                    RdWorkflowGraphConfig.setRequestModel(resumeModel);
                }
                try {
                    // 注册 threadId 到 SseStreamingInterceptor
                    registerSseInterceptors(threadId);
                    AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
                    Disposable disposable = rdWorkflowCompiledGraph.stream(null, resumeConfig)
                            .subscribe(
                                    lastOutput::set,
                                    e -> {
                                        activeStreams.remove(threadId);
                                        log.error("恢复流程异常", e);
                                        pushErrorToSse(threadId, e);
                                        handleWorkflowException(threadId, resumeConfig, e instanceof Exception ex ? ex : new RuntimeException(e));
                                    },
                                    () -> {
                                        activeStreams.remove(threadId);
                                        pushFinalState(resumeConfig, lastOutput.get());
                                        log.info("工作流恢复执行完成: threadId={}", threadId);
                                    }
                            );
                    activeStreams.put(threadId, disposable);
                } finally {
                    if (resumeModel != null) {
                        RdWorkflowGraphConfig.clearRequestModel();
                    }
                }
            }, executor);

            return RdWorkflowResultVO.builder()
                    .threadId(threadId)
                    .status(RdWorkflowStatus.RUNNING)
                    .message("审核决策已提交，工作流继续执行中...")
                    .build();
        } catch (Exception e) {
            log.error("工作流恢复失败, threadId={}, decision={}", threadId, decision, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "工作流恢复失败: " + e.getMessage());
        }
    }

    /**
     * 从 checkpoint 恢复执行（服务重启后）。
     * <p>
     * 读取 checkpoint 中的持久化状态，重建沙箱（如需要），从中断点继续执行工作流。
     * WAITING_REVIEW 状态的流程应使用审核界面，不走此方法。
     */
    public RdWorkflowResultVO recover(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "threadId 不能为空");
        }
        WorkflowMetadata meta = metadataMapper.selectById(threadId);
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "流程不存在");
        }

        // 防重：已有活跃流在执行中，不允许重复恢复。
        // 但如果状态已为 FAILED / TERMINATED（如 start 的 put/subscribe 竞态残留，或已终止流程），
        // 清除残留（并切断残留流）后允许恢复。
        if (activeStreams.containsKey(threadId)) {
            String st = meta.getStatus();
            if ("FAILED".equals(st) || "TERMINATED".equals(st)) {
                log.warn("recover: 清除 activeStreams 残留条目（status={}，允许恢复）, threadId={}", st, threadId);
                Disposable stale = activeStreams.remove(threadId);
                if (stale != null && !stale.isDisposed()) {
                    stale.dispose();
                }
            } else {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "该流程正在执行中，请勿重复恢复");
            }
        }

        // 清理上次异常残留的错误状态
        errorStatusDetails.remove(threadId);

        RunnableConfig config = buildConfig(threadId);

        try {
            // 0. 前置检查：读 checkpoint，判断 WAITING_REVIEW + 定位续跑点
            // 确定性读取最新 checkpoint（绕过框架 MysqlSaver 的同秒排序不稳定问题）
            Map<String, Object> recoverStateData = queryLatestState(threadId);
            String resumeNode = null;
            if (recoverStateData != null) {
                OverAllState state = stateSerializer.stateOf(recoverStateData);
                String workflowStatus = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.WORKFLOW_STATUS, "");
                if ("WAITING_REVIEW".equals(workflowStatus)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "该流程当前处于等待人工审核状态，请通过审核界面操作");
                }
                resumeNode = determineResumeNode(state);
                // FAILED 恢复：跳过 validation，从 code_project_write 重新开始
                // （checkpoint state 中的 WORKFLOW_STATUS 可能未更新，以 metadata 为准）
                if ("validation".equals(resumeNode) && "FAILED".equals(meta.getStatus())) {
                    log.info("[RECOVER] FAILED 恢复，路由到 code_project_write: threadId={}", threadId);
                    resumeNode = "code_project_write";
                }
                if (resumeNode != null) {
                    log.info("[RECOVER] 定位续跑点: {}, threadId={}", resumeNode, threadId);
                    Map<String, Object> clearState = new HashMap<>();
                    // 保留原 REPAIR_COUNT 让轮次接着走；仅当已到 maxRepairIterations 上限时
                    // 重置为 max-2（给恢复后的流程至少留 2 轮），避免恢复后立刻 max_repair
                    int origRepairCount = Integer.parseInt(
                            state.value(RdWorkflowKeys.REPAIR_COUNT, 0).toString());
                    int restoredRepairCount = origRepairCount >= maxRepairIterations
                            ? Math.max(0, maxRepairIterations - 2)
                            : origRepairCount;
                    if (restoredRepairCount != origRepairCount) {
                        log.info("[RECOVER] REPAIR_COUNT {} → {}（超出上限，留 2 轮余量）, threadId={}",
                                origRepairCount, restoredRepairCount, threadId);
                    }
                    clearState.put(RdWorkflowKeys.REPAIR_COUNT, restoredRepairCount);
                    clearState.put(RdWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
                    // 续跑 decomposition_gate 时清掉推理结果，防部分完成的推理被跳过
                    if ("decomposition_gate".equals(resumeNode)) {
                        clearState.put(RdWorkflowKeys.PARALLEL_REASONING_RESULT, null);
                        for (String key : state.data().keySet()) {
                            if (key.startsWith("reasoning_") && key.endsWith("_result")) {
                                clearState.put(key, null);
                            }
                        }
                    }
                    config = rdWorkflowCompiledGraph.updateState(config, clearState, resumeNode);
                    // 关键：updateState 只更新 state 数据，不改 checkpoint.nextNodeId。
                    // 修复耗尽类 FAILED 流程正常走到 END，nextNodeId=__END__，
                    // 恢复时 GraphRunnerContext 会直接结束。这里手动重置为 resumeNode。
                    final String resetResumeNode = resumeNode;
                    final RunnableConfig resetConfig = config;
                    if (rdWorkflowSaver != null) {
                        try {
                            rdWorkflowSaver.get(resetConfig).ifPresent(cp -> {
                                try {
                                    com.alibaba.cloud.ai.graph.checkpoint.Checkpoint newCp =
                                            com.alibaba.cloud.ai.graph.checkpoint.Checkpoint.builder()
                                                    .id(cp.getId())
                                                    .state(cp.getState())
                                                    .nodeId(cp.getNodeId())
                                                    .nextNodeId(resetResumeNode)
                                                    .build();
                                    rdWorkflowSaver.put(resetConfig, newCp);
                                    log.info("[RECOVER] 重置 checkpoint.nextNodeId: {} → {} (threadId={})",
                                            cp.getNextNodeId(), resetResumeNode, threadId);
                                } catch (Exception e) {
                                    log.warn("[RECOVER] 重置 checkpoint.nextNodeId 失败: {}", e.getMessage());
                                }
                            });
                        } catch (Exception e) {
                            log.warn("[RECOVER] 读取 checkpoint 失败: {}", e.getMessage());
                        }
                    }
                } else {
                    log.info("[RECOVER] 无法定位续跑点，从头执行: threadId={}", threadId);
                }

                // 沙箱预检：续跑点在 sandbox_init 之后时，提前重建容器
                if (isAfterSandbox(state, resumeNode)) {
                    String projectRoot = RdWorkflowKeys.extractStateText(state, CodeProjectWriteNode.CODE_PROJECT_ROOT, "");
                    if (!projectRoot.isBlank()) {
                        // 检查磁盘目录是否存在（可能被系统清理）
                        java.nio.file.Path projectPath = java.nio.file.Path.of(projectRoot);
                        if (!java.nio.file.Files.exists(projectPath)) {
                            log.warn("[RECOVER] 项目目录不存在: {}, 尝试恢复...", projectRoot);
                            // 1. 先尝试从 Git clone
                            boolean restored = false;
                            if (gitService != null) {
                                try {
                                    java.nio.file.Path cloned = gitService.cloneIfExists(threadId);
                                    if (cloned != null) {
                                        projectRoot = cloned.toString();
                                        Map<String, Object> update = Map.of(
                                                CodeProjectWriteNode.CODE_PROJECT_ROOT, projectRoot);
                                        config = rdWorkflowCompiledGraph.updateState(config, update, resumeNode);
                                        log.info("[RECOVER] 已从 Git clone 恢复代码: {}", projectRoot);
                                        restored = true;
                                    }
                                } catch (com.agenthub.ai.workflow.exception.WorkflowInfraException e) {
                                    throw e; // 服务器错误 → 中断显示异常
                                } catch (Exception e) {
                                    log.warn("[RECOVER] Git clone 恢复失败: {}", e.getMessage());
                                }
                            }
                            if (!restored) {
                                // 2. Git 不可用 → 降级到 checkpoint 重写盘
                                String generatedCode = RdWorkflowKeys.extractStateText(state, "generated_code", "");
                                if (!generatedCode.isBlank()) {
                                    log.info("[RECOVER] 降级到 checkpoint 代码重写盘, threadId={}", threadId);
                                    resumeNode = "code_project_write";
                                } else {
                                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                                            "项目代码丢失且无可恢复的数据源（Git 和 checkpoint 均不可用）");
                                }
                            }
                        }
                    }
                    // 如果 resumeNode 被改为 code_project_write，跳过沙箱预检
                    if (isAfterSandbox(state, resumeNode) && !projectRoot.isBlank()) {
                        // 重启后内存 Map 丢失，先恢复初始代码生成目录和修复目录。
                        // 必须在沙箱重建之前执行（独立于沙箱 try 块）：
                        // 即使沙箱重建失败，context 也要恢复，否则修复 Agent 首次写文件
                        // 会误判为首次创建，重建空目录丢失已修复内容。
                        SandboxContext.setInitialProjectRoot(threadId, projectRoot);
                        String repairRoot = CodeProjectWriter.findRepairRootForThread(threadId);
                        if (repairRoot != null) {
                            SandboxContext.setRepairProjectRootForThread(threadId, repairRoot);
                            log.info("[RECOVER] 找回修复目录: {} (threadId={})", repairRoot, threadId);
                        }
                        try {
                            String containerId = sandboxService.reuseOrCreateSandbox(threadId, projectRoot);
                            SandboxContext.initForThread(threadId, projectRoot, containerId);
                            log.info("沙箱已重建: threadId={}, containerId={}", threadId,
                                    containerId.substring(0, Math.min(8, containerId.length())));
                        } catch (Exception e) {
                            log.warn("沙箱重建失败（将依赖后续 sandbox_init 重试）: threadId={}", threadId, e);
                        }
                    }
                }
            }

            // 1. 更新元数据
            meta.setStatus("RUNNING");
            meta.setRemark("从 checkpoint 恢复");
            updateMetadataStatus(meta);

            // 2. 清空旧 SSE 事件
            eventBus.clearEvents(threadId);

            // 3. 异步恢复执行
            final RunnableConfig recoverConfig = config;
            CompletableFuture.runAsync(() -> {
                registerSseInterceptors(threadId);
                AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
                Disposable disposable = rdWorkflowCompiledGraph.stream(null, recoverConfig)
                        .subscribe(
                                lastOutput::set,
                                e -> {
                                    activeStreams.remove(threadId);
                                    pushErrorToSse(threadId, e);
                                    log.error("恢复执行异常: threadId={}", threadId, e);
                                    handleWorkflowException(threadId, recoverConfig,
                                            e instanceof Exception ex ? ex : new RuntimeException(e));
                                },
                                () -> {
                                    activeStreams.remove(threadId);
                                    pushFinalState(recoverConfig, lastOutput.get());
                                    log.info("恢复执行完成: threadId={}", threadId);
                                }
                        );
                activeStreams.put(threadId, disposable);
            }, executor);

            log.info("工作流已恢复执行: threadId={}", threadId);
            return RdWorkflowResultVO.builder()
                    .threadId(threadId)
                    .status(RdWorkflowStatus.RUNNING)
                    .message("工作流已从 checkpoint 恢复执行")
                    .build();
        } catch (Exception e) {
            activeStreams.remove(threadId);
            log.error("恢复执行失败: threadId={}", threadId, e);
            throw e instanceof BusinessException ? (BusinessException) e
                    : new BusinessException(ErrorCode.OPERATION_ERROR, "恢复执行失败: " + e.getMessage());
        }
    }

    /**
     * 根据 checkpoint state 判断工作流中断位置，返回应续跑的节点名。
     *
     * @return 续跑节点名，null 表示无法定位（从头跑）
     */
    private String determineResumeNode(OverAllState state) {
        // 优先级从后往前：越后面越精确
        if (hasStateValue(state, RdWorkflowKeys.HARNESS_RESULT)) {
            // FAILED 恢复时跳过 validation，从 code_project_write 重新开始
            // （避免 REPAIR_COUNT 残留导致立刻 max_repair）
            String status = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.WORKFLOW_STATUS, "");
            if ("FAILED".equals(status)) {
                log.info("[RECOVER] checkpoint WORKFLOW_STATUS=FAILED，路由到 code_project_write");
                return "code_project_write";
            }
            return "validation";
        }
        if (hasStateValue(state, RdWorkflowKeys.GENERATED_CODE)) {
            // 从 code_project_write 续跑：重新写盘（旧目录已删除）+ sandbox_init
            return "code_project_write";
        }
        String decision = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.REVIEW_DECISION, "");
        if ("APPROVED".equals(decision)) {
            return "code_generation";
        }
        if (hasStateValue(state, RdWorkflowKeys.PARALLEL_REASONING_RESULT)) {
            return RdWorkflowGraphConfig.MANUAL_REVIEW_NODE;
        }
        // 并行推理是并发的：checkpoint 要么全部完成、要么全未开始，不会有中间态
        if (hasStateValue(state, RdWorkflowKeys.DECOMPOSITION_RESULT)) {
            return "decomposition_gate";
        }
        return null;
    }

    /**
     * 判断续跑点是否在沙箱初始化之后（此时需提前重建容器）。
     */
    private boolean isAfterSandbox(OverAllState state, String resumeNode) {
        if (resumeNode == null) return false;
        return "validation".equals(resumeNode) || "harness".equals(resumeNode);
    }

    private static boolean hasStateValue(OverAllState state, String key) {
        return state.value(key).map(v -> {
            if (v instanceof String s) return !s.isBlank();
            return true;
        }).orElse(false);
    }

    /**
     * 查询工作流当前状态（支持 RUNNING 状态下的进度查询）。
     * <p>
     * 如果工作流刚启动、状态尚未写入 checkpoint，返回 RUNNING 状态；
     * 如果 checkpoint 中已有部分节点输出，则正常返回当前 State。
     */
    public RdWorkflowResultVO getState(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "threadId 不能为空");
        }
        try {
            // 确定性读取最新 checkpoint（绕过框架 MysqlSaver 的同秒排序不稳定问题）
            Map<String, Object> stateData = queryLatestState(threadId);
            if (stateData == null) {
                return RdWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RdWorkflowStatus.RUNNING)
                        .message("工作流正在初始化中...")
                        .build();
            }
            OverAllState state = stateSerializer.stateOf(stateData);

            RdWorkflowStatus status = resolveStatus(state, false);

            // 元数据表兜底：重启后 checkpoint 可能缺失终态标记，以元数据表为准。
            // WAITING_REVIEW 同样存在 checkpoint 写入延迟（ManualReviewNode 发布 SSE 时
            // checkpoint 尚未完成），也由元数据表兜底。
            WorkflowMetadata metadata = metadataMapper.selectById(threadId);
            if (metadata != null && ("WAITING_REVIEW".equals(metadata.getStatus()) || isTerminal(metadata.getStatus()))) {
                status = RdWorkflowStatus.valueOf(metadata.getStatus());
            }
            // harness 三 true 兜底：resolveStatus / metadata 可能因 checkpoint 旧值误判 FAILED，
            // 但 harness_result 三个 true 说明验证确实通过了，强制覆盖为 COMPLETED
            if (status == RdWorkflowStatus.FAILED || status == RdWorkflowStatus.TERMINATED) {
                String harnessText = RdWorkflowKeys.extractStateText(state,
                        RdWorkflowKeys.HARNESS_RESULT, "");
                if (!harnessText.isBlank() && harnessThreeTrue(harnessText)) {
                    log.warn("getState: harness 三个 true，覆盖状态 {} → COMPLETED (threadId={})",
                            status, threadId);
                    status = RdWorkflowStatus.COMPLETED;
                    // 同步修复 metadata：纠正之前 pushFinalState 误判写入的 FAILED 状态
                    if (metadata != null && !"COMPLETED".equals(metadata.getStatus())) {
                        metadata.setStatus("COMPLETED");
                        metadataMapper.updateById(metadata);
                    }
                }
            }
            String message = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.WORKFLOW_MESSAGE, "");
            if (status == RdWorkflowStatus.RUNNING && message.isBlank()) {
                message = "工作流执行中...";
            } else if (status == RdWorkflowStatus.FAILED || status == RdWorkflowStatus.TERMINATED) {
                // 优先从 metadata remark 读取失败/终止原因（pushFinalState 已写入，比 checkpoint 的
                // workflow_message 可靠——后者可能因异步写入延迟仍为旧值，如"第 N 次代码修复"）
                if (metadata != null && metadata.getRemark() != null && !metadata.getRemark().isBlank()) {
                    message = metadata.getRemark();
                } else if (message.isBlank() || message.equals("工作流已启动")) {
                    if (status == RdWorkflowStatus.FAILED) {
                        message = "工作流执行失败";
                    }
                }
            }
            Map<String, Object> sanitized = sanitizeStateForSerialization(state.data());
            // 终态补全：仅当沙箱确实跑过时，补上 checkpoint 可能缺失的 validation_passed
            if (isTerminal(status.name()) && sanitized.containsKey(RdWorkflowKeys.HARNESS_RESULT)
                    && !sanitized.containsKey(RdWorkflowKeys.VALIDATION_PASSED)) {
                sanitized.put(RdWorkflowKeys.VALIDATION_PASSED, true);
            }
            // harnessThreeTrue 兜底后同步修正返回字段（前端渲染依赖这些原始值）
            if (status == RdWorkflowStatus.COMPLETED) {
                Object vpObj = sanitized.get(RdWorkflowKeys.VALIDATION_PASSED);
                if (!Boolean.TRUE.equals(vpObj) && !"true".equalsIgnoreCase(String.valueOf(vpObj))) {
                    sanitized.put(RdWorkflowKeys.VALIDATION_PASSED, true);
                }
                sanitized.put(RdWorkflowKeys.WORKFLOW_STATUS, "COMPLETED");
            }
            return RdWorkflowResultVO.builder()
                    .threadId(threadId)
                    .status(status)
                    .interrupted(status == RdWorkflowStatus.WAITING_REVIEW)
                    .interruptedNode(status == RdWorkflowStatus.WAITING_REVIEW
                            ? RdWorkflowGraphConfig.MANUAL_REVIEW_NODE : null)
                    .state(sanitized)
                    .message(message)
                    .build();
        } catch (IllegalStateException e) {
            // 工作流刚启动，checkpoint 尚未写入，返回 RUNNING
            if (e.getMessage() != null && e.getMessage().contains("Missing Checkpoint")) {
                log.debug("工作流 checkpoint 尚未就绪: threadId={}", threadId);
                return RdWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RdWorkflowStatus.RUNNING)
                        .message("工作流正在启动中...")
                        .build();
            }
            throw e;
        } catch (Exception e) {
            log.error("查询工作流状态失败, threadId={}", threadId, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "查询工作流状态失败: " + e.getMessage());
        }
    }

    /**
     * 查询历史工作流列表。基于独立元数据表，支持 SQL 索引查询，可支撑千万级数据。
     */
    public com.agenthub.ai.base.common.PageResult listRecords(
            com.agenthub.ai.workflow.dto.WorkflowRecordQueryDTO query) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkflowMetadata> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();

        if (query.getStartDate() != null && !query.getStartDate().isBlank()) {
            wrapper.ge(WorkflowMetadata::getCreateTime, query.getStartDate() + " 00:00:00");
        }
        if (query.getEndDate() != null && !query.getEndDate().isBlank()) {
            wrapper.le(WorkflowMetadata::getCreateTime, query.getEndDate() + " 23:59:59");
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(WorkflowMetadata::getStatus, query.getStatus());
        }
        if (query.getRequirement() != null && !query.getRequirement().isBlank()) {
            wrapper.like(WorkflowMetadata::getRequirement, query.getRequirement());
        }
        if (query.getThreadId() != null && !query.getThreadId().isBlank()) {
            wrapper.like(WorkflowMetadata::getThreadId, query.getThreadId());
        }
        wrapper.orderByDesc(WorkflowMetadata::getCreateTime);

        int page = query.getPage() != null && query.getPage() > 0 ? query.getPage() : 1;
        int pageSize = query.getPageSize() != null && query.getPageSize() > 0 ? query.getPageSize() : 15;

        // MyBatis-Plus 分页
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WorkflowMetadata> pageParam =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(page, pageSize);
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<WorkflowMetadata> pageResult =
                metadataMapper.selectPage(pageParam, wrapper);

        java.util.List<com.agenthub.ai.workflow.vo.WorkflowRecordVO> records = pageResult.getRecords().stream()
                .map(r -> com.agenthub.ai.workflow.vo.WorkflowRecordVO.builder()
                        .threadId(r.getThreadId())
                        .requirement(r.getRequirement())
                        .reviewFeedback(r.getReviewFeedback())
                        .status(r.getStatus())
                        .createTime(r.getCreateTime())
                        .updateTime(r.getUpdateTime())
                        .remark(r.getRemark())
                        .build())
                .toList();

        return new com.agenthub.ai.base.common.PageResult(pageResult.getTotal(), records);
    }

    private RdWorkflowResultVO buildResult(String threadId, Optional<NodeOutput> outputOpt, RunnableConfig config) {
        if (outputOpt.isEmpty()) {
            return RdWorkflowResultVO.builder()
                    .threadId(threadId)
                    .status(RdWorkflowStatus.FAILED)
                    .message("工作流无输出")
                    .interrupted(false)
                    .build();
        }

        NodeOutput output = outputOpt.get();

        if (output instanceof InterruptionMetadata interruption) {
            return RdWorkflowResultVO.builder()
                    .threadId(threadId)
                    .status(RdWorkflowStatus.WAITING_REVIEW)
                    .interrupted(true)
                    .interruptedNode(interruption.node())
                    .state(sanitizeStateForSerialization(interruption.state().data()))
                    .message("流程已暂停，等待人工审核推理结果。请调用 /resume 接口提交 APPROVED/SENT_BACK/TERMINATED 决策")
                    .build();
        }

        OverAllState state = output.state();
        RdWorkflowStatus status = resolveStatus(state, false);
        String message = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.WORKFLOW_MESSAGE, "");

        if (status == RdWorkflowStatus.COMPLETED) {
            message = message.isBlank() ? "工作流执行完成" : message;
        } else if (status == RdWorkflowStatus.TERMINATED) {
            // 优先从 metadata remark 读取终止原因（人工审核拒绝等），
            // checkpoint 中的 workflow_message 可能仍是初始值
            if (message.isBlank() || message.equals("工作流已启动") || message.equals("工作流已终止")) {
                WorkflowMetadata meta = metadataMapper.selectById(threadId);
                if (meta != null && meta.getRemark() != null && !meta.getRemark().isBlank()) {
                    message = meta.getRemark();
                } else {
                    message = "工作流已终止";
                }
            }
        } else if (status == RdWorkflowStatus.FAILED) {
            // 优先从 metadata remark 读取失败原因（pushErrorToSse/handleWorkflowException 写入），
            // checkpoint 中的 workflow_message 可能仍是初始值 "工作流已启动"
            if (message.isBlank() || message.equals("工作流已启动")) {
                WorkflowMetadata meta = metadataMapper.selectById(threadId);
                if (meta != null && meta.getRemark() != null && !meta.getRemark().isBlank()) {
                    message = meta.getRemark();
                } else {
                    message = "工作流执行失败";
                }
            }
        }

        return RdWorkflowResultVO.builder()
                .threadId(threadId)
                .status(status)
                .interrupted(false)
                .state(sanitizeStateForSerialization(state.data()))
                .message(message)
                .build();
    }

    private RdWorkflowStatus resolveStatus(OverAllState state, boolean interrupted) {
        if (interrupted) {
            return RdWorkflowStatus.WAITING_REVIEW;
        }
        // 非研发需求：拆解 Agent 输出 [NOT_DEV_REQ] 标记，工作流已正常结束（非异常）
        String decompResult = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.DECOMPOSITION_RESULT, "");
        if (RdWorkflowKeys.isNotDevReq(decompResult)) {
            return RdWorkflowStatus.COMPLETED;
        }
        String reviewDecision = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.REVIEW_DECISION, "");
        if ("TERMINATED".equals(reviewDecision)) {
            return RdWorkflowStatus.TERMINATED;
        }
        boolean validationPassed = Boolean.parseBoolean(
                state.value(RdWorkflowKeys.VALIDATION_PASSED).map(Object::toString).orElse("false"));
        if (validationPassed) {
            return RdWorkflowStatus.COMPLETED;
        }
        int repairCount = Integer.parseInt(state.value(RdWorkflowKeys.REPAIR_COUNT, 0).toString());
        if (repairCount >= maxRepairIterations && !validationPassed) {
            // 修复循环耗尽次数且验证未通过 → FAILED
            // 不依赖 harness_result.isPresent()，因为修复循环可能因"没写文件"重试耗尽次数，
            // 此时跳过了沙箱验证，harness_result 可能为空或停留在旧值
            return RdWorkflowStatus.FAILED;
        }
        String workflowStatus = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.WORKFLOW_STATUS, "");
        if ("WAITING_REVIEW".equals(workflowStatus)) {
            return RdWorkflowStatus.WAITING_REVIEW;
        }
        if ("TERMINATED".equals(workflowStatus)) {
            return RdWorkflowStatus.TERMINATED;
        }
        if ("FAILED".equals(workflowStatus)) {
            return RdWorkflowStatus.FAILED;
        }
        if ("COMPLETED".equals(workflowStatus)) {
            return RdWorkflowStatus.COMPLETED;
        }
        return RdWorkflowStatus.RUNNING;
    }

    private Map<String, Object> sanitizeStateForSerialization(Map<String, Object> stateData) {
        if (stateData == null || stateData.isEmpty()) {
            return stateData;
        }
        return stateData.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> sanitizeValue(entry.getValue())
                ));
    }

    /**
     * 递归提取任意类型值为可序列化文本。
     */
    private Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        // Spring AI Message → 提取文本
        if (value instanceof org.springframework.ai.chat.messages.Message msg) {
            return msg.getText();
        }
        // 基础类型直接返回
        if (value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Enum) {
            return value;
        }
        // 嵌套 Map 递归处理
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapValue = (Map<String, Object>) value;
            return sanitizeStateForSerialization(mapValue);
        }
        // GraphResponse / NodeOutput 等 graph 框架对象：反射提取内层文本
        String className = value.getClass().getName();
        if (className.startsWith("com.alibaba.cloud.ai.graph")) {
            // 尝试 resultValue() → Optional<Object> → Message.getText() 或 toString
            try {
                java.lang.reflect.Method method = value.getClass().getMethod("resultValue");
                Object opt = method.invoke(value);
                if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
                    return sanitizeValue(o.get());
                }
        } catch (Exception ignored) {}
            // 降级：Object.toString()（如果是非默认实现可能有意义的内容）
            String str = value.toString();
            if (str.startsWith(className + "@")) {
                return "[Complex Object: " + className.substring(className.lastIndexOf('.') + 1) + "]";
            }
            return str;
        }
        return value.toString();
    }

    /**
     * 工作流执行完后，将所有 State 数据推送到 SSE 通道，前端拿到完整进度。
     */
    /**
     * 工作流执行完后，将所有 State 数据推送到 SSE 通道，前端拿到完整进度。
     *
     * @param config     RunnableConfig（含 threadId）
     * @param lastOutput stream() 的最后一个 NodeOutput（可能是 InterruptionMetadata）
     */
    private void pushFinalState(RunnableConfig config, NodeOutput lastOutput) {
        try {
            String threadId = config.threadId().orElse(null);
            if (threadId == null) return;

            // 始终从 checkpoint 获取最新 State（InterruptionMetadata.state() 是暂停时的旧快照，可能过时）
            // 确定性读取最新 checkpoint（绕过框架 MysqlSaver 的同秒排序不稳定问题）
            Map<String, Object> pushStateData = queryLatestState(threadId);
            if (pushStateData == null) return;
            OverAllState state = stateSerializer.stateOf(pushStateData);

            // 判断工作流是否真正暂停（被 interruptAfter 中断）
            // 可靠信号：stream() 最后一个 NodeOutput 是 InterruptionMetadata
            boolean interrupted = (lastOutput instanceof InterruptionMetadata);

            // 确定最终状态：暂停 → WAITING_REVIEW；否则用 resolveStatus() 统一判断
            // resolveStatus() 能正确识别 COMPLETED / FAILED / TERMINATED / WAITING_REVIEW / RUNNING
            String status;
            if (interrupted) {
                status = "WAITING_REVIEW";
            } else {
                RdWorkflowStatus resolved = resolveStatus(state, false);
                // 兜底：repair_exhausted 节点写 WORKFLOW_STATUS=FAILED 到 checkpoint，但存在入库延迟
                String wsOverride = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.WORKFLOW_STATUS, "");
                if ("FAILED".equals(wsOverride)) {
                    resolved = RdWorkflowStatus.FAILED;
                }
                // 修复耗尽节点 errorStatusDetails 兜底：跨线程可靠覆盖
                String failOverride = errorStatusDetails.get(threadId);
                if (failOverride != null && failOverride.startsWith("FAILED")) {
                    status = RdWorkflowStatus.FAILED.name();
                }
                // resolveStatus 可能返回 RUNNING（中间状态），此时工作流已结束
                // 需要判断是真正完成还是修复耗尽失败
                if (resolved == RdWorkflowStatus.RUNNING) {
                    int repairCount = Integer.parseInt(
                            state.value(RdWorkflowKeys.REPAIR_COUNT, 0).toString());
                    Object vpObj = state.value(RdWorkflowKeys.VALIDATION_PASSED).orElse(null);
                    boolean validationPassed = Boolean.TRUE.equals(vpObj)
                            || "true".equalsIgnoreCase(String.valueOf(vpObj));
                    // 显式验证失败：validation_passed 有值且不是 true（ValidationNode 失败时写入失败文本）
                    boolean validationFailed = vpObj != null && !validationPassed
                            && !String.valueOf(vpObj).isBlank();
                    if (validationFailed) {
                        // 验证节点明确判定失败 → FAILED（避免"验证失败但修复未耗尽"误判为 COMPLETED）
                        status = RdWorkflowStatus.FAILED.name();
                    } else if (repairCount >= maxRepairIterations && !validationPassed) {
                        status = RdWorkflowStatus.FAILED.name();
                    } else {
                        // 检查 doOnError 是否记录了异常（pushErrorToSse 不再发送 workflow_status，
                        // ReactAgent onErrorResume 可能吞掉异常导致 stream 正常完成但 state 仍为 RUNNING）
                        String errorDetail = errorStatusDetails.get(threadId);
                        if (errorDetail != null) {
                            status = RdWorkflowStatus.FAILED.name();
                        } else {
                            status = RdWorkflowStatus.COMPLETED.name();
                        }
                    }
                } else {
                    status = resolved.name();
                }
            }

            // 兜底：即使 checkpoint 中遗留旧值导致判定为 FAILED/RUNNING，
            // 如果 harness_result 显示三个 true（验证确实通过了），强制覆盖为 COMPLETED。
            // recover 场景下 checkpoint 可能残留修复循环中途的 WORKFLOW_STATUS 旧值，
            // 导致 resolveStatus 误判。
            if (!"COMPLETED".equals(status) && !"WAITING_REVIEW".equals(status)
                    && !"TERMINATED".equals(status)) {
                String harnessText = RdWorkflowKeys.extractStateText(state,
                        RdWorkflowKeys.HARNESS_RESULT, "");
                if (!harnessText.isBlank() && harnessThreeTrue(harnessText)) {
                    log.warn("pushFinalState: harness 三个 true，覆盖状态 {} → COMPLETED (threadId={})",
                            status, threadId);
                    status = RdWorkflowStatus.COMPLETED.name();
                    // 直接推送修正值，覆盖前端 state 中的旧 validation_passed
                    eventBus.publish(threadId, RdWorkflowKeys.VALIDATION_PASSED, "true", status);
                    eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS, "COMPLETED", status);
                }
            }

            log.info("pushFinalState: threadId={}, interrupted={}, status={}, review_feedback='{}'",
                    threadId, interrupted, status,
                    RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.REVIEW_FEEDBACK, ""));

            // 更新元数据表最终状态
            WorkflowMetadata meta = new WorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setStatus(status);
            // FAILED 时将失败原因写入备注（errorStatusDetails 由降级失败/异常节点写入，
            // 比 checkpoint 的 workflow_message 更可靠——后者可能因异步写入延迟未被持久化）
            if ("FAILED".equals(status)) {
                String failReason = errorStatusDetails.get(threadId);
                if (failReason != null) {
                    meta.setRemark(failReason.replaceFirst("^FAILED — ", ""));
                }
            }
            // COMPLETED 时写入备注：git.enabled=true（推送远程）只写项目目录名即可定位；
            // git.enabled=false（仅本地）写完整路径便于直接找到项目目录。
            if ("COMPLETED".equals(status)) {
                String repairRoot = SandboxContext.getRepairProjectRootForThread(threadId);
                String projectRoot = repairRoot != null && !repairRoot.isBlank()
                        ? repairRoot
                        : RdWorkflowKeys.extractStateText(state,
                                CodeProjectWriteNode.CODE_PROJECT_ROOT, "");
                if (!projectRoot.isBlank()) {
                    if (gitService != null) {
                        // 已推送到 git → 只需项目名（对应 git 仓库 projects-{threadId} 的代码）
                        String dirName = java.nio.file.Path.of(projectRoot).getFileName().toString();
                        meta.setRemark("推送git：" + dirName);
                    } else {
                        // 仅本地 → 完整路径便于直接定位
                        meta.setRemark("磁盘路径：" + projectRoot);
                    }
                }
                // 兜底补推：若之前各轮 push 都失败（网络/token 波动），
                // 工作流结束时再异步尝试一次，git 恢复后自动补齐
                if (gitService != null) {
                    try {
                        String finalDir = (repairRoot != null && !repairRoot.isBlank())
                                ? repairRoot : RdWorkflowKeys.extractStateText(state,
                                        CodeProjectWriteNode.CODE_PROJECT_ROOT, "");
                        if (!finalDir.isBlank()) {
                            gitService.pushAsync(java.nio.file.Path.of(finalDir), threadId);
                        }
                    } catch (Exception e) {
                        log.warn("Git 兜底补推失败（不影响 COMPLETED）: {}", e.getMessage());
                    }
                }
            }
            updateMetadataStatus(meta);

            // 内容字段用 RUNNING 推送，避免首条事件携带终态导致 SSE 提前关闭、后续字段丢失
            // ⚠️ 跳过 harness_result 和 generated_code：
            // 这两个字段已由各 Agent 的 SseStreamingInterceptor 在 afterStreamComplete 中
            // 通过 extractSummary 过滤后推送，直接推送原始内容会覆盖过滤后的横幅展示。
            for (Map.Entry<String, Object> entry : state.data().entrySet()) {
                String key = entry.getKey();
                if (RdWorkflowKeys.HARNESS_RESULT.equals(key)
                        || RdWorkflowKeys.GENERATED_CODE.equals(key)
                        || RdWorkflowKeys.CODE_REPAIR_ANALYSIS.equals(key)) {
                    continue;
                }
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                eventBus.publish(threadId, key, value, "RUNNING");
            }
            // 仅 workflow_status 携带终态，供 SSE 端点识别并关闭连接
            // 如果 pushErrorToSse 已在 doOnError 中推送了详细错误状态，优先使用
            String errorDetail = errorStatusDetails.remove(threadId);
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS,
                    errorDetail != null && status.equals("FAILED") ? errorDetail : status, status);

            // 工作流结束，销毁沙箱容器（修复循环中容器保持存活以复用 Maven 缓存）
            try {
                sandboxService.destroySandbox(threadId);
            } catch (Exception e) {
                log.warn("销毁沙箱容器失败: threadId={}", threadId, e);
            }

            // 清理临时项目目录（codegen-{threadId}-* / codefix-{threadId}-*），避免累积。
            // ⚠️ COMPLETED 时保留磁盘目录：这是最终生成的项目文件，用户需要下载/查看。
            // 仅失败/终止/异常时清理，防止临时目录无限累积。
            if (!"COMPLETED".equals(status)) {
                try {
                    CodeProjectWriter.cleanupForThread(threadId);
                } catch (Exception e) {
                    log.warn("清理临时目录失败: threadId={}", threadId, e);
                }
            }
            // 内存态始终清理，防止跨工作流泄漏
            try {
                SandboxContext.clearAll(threadId);
                CodeRepairTools.clearToolUsage(threadId);
            } catch (Exception e) {
                log.warn("清理内存上下文失败: threadId={}", threadId, e);
            }
        } catch (Exception e) {
            log.error("pushFinalState 内部失败，工作流终态可能丢失", e);
            // 兜底：确保 FAILED 状态至少推送到 SSE
            try {
                String threadId = config.threadId().orElse(null);
                if (threadId != null && eventBus != null) {
                    eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS,
                            "FAILED — 系统异常", "FAILED");
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * 更新元数据表状态（仅更新 status + remark + update_time，不影响其他字段）。
     * 用于异常兜底、状态变更等只需更新少量字段的场景。
     */
    private void updateMetadataStatus(WorkflowMetadata meta) {
        try {
            meta.setUpdateTime(new Date());
            metadataMapper.update(meta, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<WorkflowMetadata>()
                    .eq(WorkflowMetadata::getThreadId, meta.getThreadId())
                    .set(WorkflowMetadata::getStatus, meta.getStatus())
                    .set(WorkflowMetadata::getRemark, meta.getRemark())
                    .set(WorkflowMetadata::getUpdateTime, meta.getUpdateTime()));
        } catch (Exception e) {
            log.error("更新元数据失败: threadId={}", meta.getThreadId(), e);
        }
    }

    /**
     * 统一处理工作流执行异常。
     * <p>
     * 识别常见 LLM API 错误（403 额度不足、429 限流、超时等），
     * 提供用户友好的错误信息推送到 SSE，并标记工作流 FAILED。
     */
    private void handleWorkflowException(String threadId, RunnableConfig config, Exception e) {
        try {
            String friendlyMsg = toFriendlyErrorMessage(e);
            String errorStatus = "FAILED — " + truncate(friendlyMsg, 80);
            errorStatusDetails.put(threadId, errorStatus);
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, friendlyMsg, "RUNNING");
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS, errorStatus, "FAILED");
            log.info("异常已推送到SSE: threadId={}, msg={}", threadId, truncate(friendlyMsg, 50));
            WorkflowMetadata meta = new WorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setStatus("FAILED");
            meta.setRemark(truncate(friendlyMsg, 300));
            updateMetadataStatus(meta);
        } catch (Exception ex) {
            log.error("handleWorkflowException 内部失败，异常状态可能丢失: threadId={}", threadId, ex);
        }
    }

    private void pushErrorToSse(String threadId, Throwable e) {
        try {
            String friendlyMsg = toFriendlyErrorMessage(e);
            String errorStatus = "FAILED — " + truncate(friendlyMsg, 80);
            errorStatusDetails.put(threadId, errorStatus);
            // 只发布错误消息，不发布 workflow_status
            // doOnError 触发时数据库元数据可能尚未更新为 FAILED，
            // 发布 FAILED 会导致前端提前触发 handleWorkflowFinished 重新监听，丢失 SSE 累积的 state
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, friendlyMsg, "RUNNING");
            WorkflowMetadata meta = new WorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setStatus("FAILED");
            meta.setRemark(truncate(friendlyMsg, 300));
            updateMetadataStatus(meta);
        } catch (Exception ex) {
            log.error("pushErrorToSse 内部失败，异常状态可能丢失: threadId={}", threadId, ex);
        }
    }

    /**
     * 将底层异常转换为用户友好的错误信息。
     */
    private String toFriendlyErrorMessage(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String className = e.getClass().getSimpleName();

        // 递归获取所有 cause 的类名，用于匹配（WebClientRequestException.getMessage() 常返回 null）
        String fullClassChain = getClassChain(e);

        // 基础设施异常（Git、Docker、沙箱等）已由节点包装为友好信息，直接返回不做 LLM 匹配
        if (e instanceof com.agenthub.ai.workflow.exception.WorkflowInfraException
                || findCauseOfType(e, com.agenthub.ai.workflow.exception.WorkflowInfraException.class) != null) {
            return msg;
        }

        // 403 Forbidden — API Key 额度不足或权限问题
        if (msg.contains("403") || msg.contains("Forbidden")) {
            return "LLM API 返回 403 Forbidden — 可能是 API Key 额度已用完或权限不足。"
                    + "请检查 LLM API Key 的额度和权限设置。"
                    + "（原始错误: " + truncate(msg, 200) + "）";
        }
        // 429 Too Many Requests — 限流
        if (msg.contains("429") || msg.contains("Too Many Requests")) {
            return "LLM API 返回 429 — 请求过于频繁被限流，请稍后重试。"
                    + "（原始错误: " + truncate(msg, 200) + "）";
        }
        // 连接超时 / 网络错误
        if (msg.contains("timeout") || msg.contains("Timeout")
                || className.contains("Timeout") || fullClassChain.contains("Timeout")) {
            return "LLM API 请求超时 — 请检查网络连接或稍后重试。"
                    + "（原始错误: " + truncate(msg, 200) + "）";
        }
        // 连接被拒绝 / 网络不可达（检查 cause 链）
        if (msg.contains("Connection refused") || fullClassChain.contains("ConnectException")
                || fullClassChain.contains("ClosedChannelException")) {
            return "无法连接到 LLM API 服务 — 请检查 LLM 服务是否正常运行、网络是否可达。"
                    + "（原始错误: " + truncate(msg, 200) + "）";
        }
        // No ToolCallback — LLM 幻觉工具名
        if (msg.contains("No ToolCallback found")) {
            return "LLM 尝试调用不存在的工具（可能是模型幻觉），请重试。"
                    + "（原始错误: " + truncate(msg, 200) + "）";
        }
        // 兜底
        return "工作流执行异常: " + className + " — " + truncate(msg, 300);
    }

    /** 递归获取异常类名链（包含所有 cause），用 , 分隔 */
    private String getClassChain(Throwable e) {
        if (e == null) return "";
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName());
        Throwable cause = e.getCause();
        while (cause != null) {
            sb.append(",").append(cause.getClass().getSimpleName());
            cause = cause.getCause();
        }
        return sb.toString();
    }

    /** 递归查找异常链中是否存在指定类型的异常（包含自身及所有 cause） */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T findCauseOfType(Throwable e, Class<T> type) {
        Throwable current = e;
        while (current != null) {
            if (type.isInstance(current)) return (T) current;
            current = current.getCause();
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * 与 ValidationNode 一致的 harness 三 true 检测，用于 pushFinalState 兜底。
     * recover 场景下 checkpoint 可能残留修复循环中途的旧 WORKFLOW_STATUS，
     * 导致 resolveStatus 误判 FAILED，此时若 harness 三个 true 应强制 COMPLETED。
     */
    private static boolean harnessThreeTrue(String text) {
        boolean compileOk = text.contains("COMPILE_SUCCESS: true")
                && !text.contains("COMPILE_SUCCESS: false");
        boolean hasRuntime = (text.contains("RUNTIME_SUCCESS: true")
                || text.contains("RUNTIME_SUCCESS: 编译通过")
                || text.contains("RUNTIME_SUCCESS: 应用正常启动"))
                && !text.contains("RUNTIME_SUCCESS: false");
        boolean testOk = text.contains("TEST_SUCCESS: true")
                && !text.contains("TEST_SUCCESS: false");
        if (!compileOk || !hasRuntime || !testOk) return false;
        // 排除假成功（LLM 没粘贴真实工具返回值，保留模板占位符）
        return !text.contains("[compileCode 返回值")
                && !text.contains("[executeInSandbox 返回值")
                && !text.contains("[runTests 返回值");
    }

    /**
     * 确定性读取最新 checkpoint state（应用层绕过框架 MysqlSaver）。
     * <p>
     * 框架 MysqlSaver 用 `ORDER BY saved_at DESC LIMIT 1` 取最新 checkpoint，
     * 但 saved_at 是秒级 TIMESTAMP，同一秒内多条 checkpoint（如 harness/validation 相邻节点）
     * 排序不稳定，导致 getState 可能返回修复循环中途的旧 state（误判 FAILED）。
     * 这里用 saved_at + checkpoint_id 双重排序（checkpoint_id 是 UUID 字符串，字典序稳定），
     * 保证确定性返回真正最新的 checkpoint。
     */
    private Map<String, Object> queryLatestState(String threadId) {
        try {
            // 第一步：只查 checkpoint_id（小字段）排序，避免 state_data 大字段参与 ORDER BY 触发 Out of sort memory
            String checkpointId = metadataMapper.selectLatestCheckpointId(threadId);
            if (checkpointId == null || checkpointId.isBlank()) return null;
            // 第二步：按主键取 state_data
            String stateData = metadataMapper.selectCheckpointStateData(checkpointId);
            if (stateData == null || stateData.isBlank()) return null;
            String base64 = extractBinaryPayload(stateData);
            if (base64 == null) return null;
            byte[] bytes = java.util.Base64.getDecoder().decode(base64);
            return stateSerializer.dataFromBytes(bytes);
        } catch (Exception e) {
            log.warn("确定性读取 checkpoint 失败: threadId={}, err={}", threadId, e.getMessage());
            return null;
        }
    }

    /** 从 state_data JSON 中提取 binaryPayload 的 base64 值（格式固定为 {"binaryPayload": "..."}） */
    private String extractBinaryPayload(String stateData) {
        int idx = stateData.indexOf("binaryPayload");
        if (idx < 0) return null;
        int start = stateData.indexOf('"', idx + "binaryPayload".length() + 1);
        if (start < 0) return null;
        int end = stateData.indexOf('"', start + 1);
        if (end < 0) return null;
        return stateData.substring(start + 1, end);
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "TERMINATED".equals(status) || "FAILED".equals(status);
    }

    /**
     * 下载流程项目代码（zip 打包）。
     * 单个流程以 projects-{threadId}/ 为根；多个流程以 download-code.zip 压缩。
     * 优先 git clone，失败回退本地 temp 目录，都失败则跳过该流程并提示。
     */
    public void downloadCode(java.util.Set<String> threadIds,
            jakarta.servlet.http.HttpServletResponse response) throws IOException {
        java.util.List<java.util.Map.Entry<String, Path>> sources = new java.util.ArrayList<>();
        java.util.List<String> failedIds = new java.util.ArrayList<>();

        for (String tid : threadIds) {
            if (tid == null || tid.isBlank()) continue;
            Path dir = null;
            // 1. 优先 git clone
            if (gitService != null) {
                dir = gitService.cloneToTemp(tid);
            }
            // 2. 回退本地目录（codefix 或 codegen）
            if (dir == null) {
                dir = findLocalProjectDir(tid);
                if (dir != null) {
                    log.info("Git clone 未找到，回退到本地目录: threadId={}, dir={}", tid, dir);
                }
            }
            if (dir != null && java.nio.file.Files.isDirectory(dir)) {
                sources.add(Map.entry(tid, dir));
            } else {
                log.warn("下载代码失败（Git 和本地均未找到）: threadId={}", tid);
                failedIds.add(tid);
            }
        }

        if (sources.isEmpty()) {
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":1,\"message\":\"所选流程暂无可下载的项目文件！\"}");
            return;
        }

        boolean single = sources.size() == 1 && failedIds.isEmpty();
        String zipName = single ? "projects-" + sources.get(0).getKey() + ".zip" : "download-code.zip";
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + zipName + "\"");
        if (!failedIds.isEmpty()) {
            response.setHeader("X-Failed-ThreadIds", String.join(",", failedIds));
        }

        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(response.getOutputStream())) {
            for (var entry : sources) {
                String prefix = "projects-" + entry.getKey() + "/";
                Path dir = entry.getValue();
                try (var stream = java.nio.file.Files.walk(dir)) {
                    stream.filter(java.nio.file.Files::isRegularFile).forEach(f -> {
                        try {
                            String name = prefix + dir.relativize(f).toString().replace("\\", "/");
                            zos.putNextEntry(new java.util.zip.ZipEntry(name));
                            java.nio.file.Files.copy(f, zos);
                            zos.closeEntry();
                        } catch (IOException ignored) {}
                    });
                }
            }
        }
    }

    /** 从本地 temp 目录查找项目文件（codefix 优先，否则 codegen） */
    private Path findLocalProjectDir(String threadId) {
        Path tempDir = getStorageDir();
        if (!java.nio.file.Files.isDirectory(tempDir)) return null;
        try (var stream = java.nio.file.Files.list(tempDir)) {
            return stream.filter(java.nio.file.Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("codefix-" + threadId + "-")
                            || p.getFileName().toString().startsWith("codegen-" + threadId + "-"))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Path getStorageDir() {
        if (codeStoragePath != null && !codeStoragePath.isBlank())
            return Path.of(codeStoragePath).normalize();
        return Path.of(System.getProperty("user.dir"), "..", "temp").normalize();
    }
}
