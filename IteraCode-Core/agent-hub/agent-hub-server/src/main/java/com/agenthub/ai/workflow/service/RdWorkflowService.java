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
import com.agenthub.ai.workflow.tool.CodeProjectWriter;
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

    /** 活跃的工作流 reactive 流，用于手动终止 */
    private final Map<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    private final CompiledGraph rdWorkflowCompiledGraph;
    private final WorkflowEventBus eventBus;
    private final WorkflowMetadataMapper metadataMapper;
    private final SkillLoader skillLoader;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Value("${agenthub.workflow.saver-type:memory}")
    private String saverType;
    private final com.alibaba.cloud.ai.graph.serializer.StateSerializer stateSerializer;
    private final GitProjectService gitService;

    @Value("${agenthub.workflow.code-storage-path:}")
    private String codeStoragePath;

    public RdWorkflowService(CompiledGraph rdWorkflowCompiledGraph, WorkflowEventBus eventBus,
            WorkflowMetadataMapper metadataMapper,
            SkillLoader skillLoader,
            com.alibaba.cloud.ai.graph.serializer.StateSerializer stateSerializer,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            GitProjectService gitService) {
        this.rdWorkflowCompiledGraph = rdWorkflowCompiledGraph;
        this.eventBus = eventBus;
        this.metadataMapper = metadataMapper;
        this.skillLoader = skillLoader;
        this.stateSerializer = stateSerializer;
        this.gitService = gitService;
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
        SseStreamingInterceptor.registerThreadId(RdWorkflowKeys.GENERATED_CODE, threadId);
    }

    /**
     * 启动研发工作流（异步）。
     * <p>
     * 实际工作流执行在独立线程中进行，HTTP 请求立即返回 threadId，
     * 前端通过轮询 /state/{threadId} 获取实时进度。
     */
    public RdWorkflowResultVO start(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "研发需求不能为空");
        }
        String threadId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> input = new HashMap<>();
        input.put(RdWorkflowKeys.REQUIREMENT, requirement.trim());
        input.put(MultiRoundAgentNode.THREAD_ID_KEY, threadId);
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
        }, executor);

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
    public RdWorkflowResultVO resume(String threadId, RdWorkflowReviewDecision decision, String comment) {
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
            CompletableFuture.runAsync(() -> {
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
        // 但如果状态已为 FAILED（如 start 的 put/subscribe 竞态残留），清除残留并允许恢复。
        if (activeStreams.containsKey(threadId)) {
            if ("FAILED".equals(meta.getStatus())) {
                log.warn("recover: 清除 activeStreams 残留条目（status=FAILED，允许恢复）, threadId={}", threadId);
                activeStreams.remove(threadId);
            } else {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "该流程正在执行中，请勿重复恢复");
            }
        }


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
                if (resumeNode != null) {
                    log.info("[RECOVER] 定位续跑点: {}, threadId={}", resumeNode, threadId);
                    Map<String, Object> clearState = new HashMap<>();
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
                } else {
                    log.info("[RECOVER] 无法定位续跑点，从头执行: threadId={}", threadId);
                }

                if (resumeNode != null) {
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
            String message = RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.WORKFLOW_MESSAGE, "");
            if (status == RdWorkflowStatus.RUNNING && message.isBlank()) {
                message = "工作流执行中...";
            } else if (status == RdWorkflowStatus.FAILED || status == RdWorkflowStatus.TERMINATED) {
                // 优先从 metadata remark 读取失败/终止原因
                if (message.isBlank() || message.equals("工作流已启动")) {
                    if (metadata != null && metadata.getRemark() != null && !metadata.getRemark().isBlank()) {
                        message = metadata.getRemark();
                    } else if (status == RdWorkflowStatus.FAILED) {
                        message = "工作流执行失败";
                    }
                }
            }
            Map<String, Object> sanitized = sanitizeStateForSerialization(state.data());
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

        // PageHelper 分页
        com.github.pagehelper.PageHelper.startPage(page, pageSize);
        com.github.pagehelper.Page<WorkflowMetadata> pageResult =
                (com.github.pagehelper.Page<WorkflowMetadata>) metadataMapper.selectList(wrapper);

        java.util.List<com.agenthub.ai.workflow.vo.WorkflowRecordVO> records = pageResult.getResult().stream()
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

            // 状态获取：优先用流最后一帧 lastOutput（当前运行，包含 code_project_root），
            // 回退到 checkpoint（恢复/重启场景，上一次运行已落库）
            OverAllState state;
            if (lastOutput != null && !(lastOutput instanceof InterruptionMetadata)) {
                state = lastOutput.state();
            } else {
                Map<String, Object> pushStateData = queryLatestState(threadId);
                if (pushStateData == null) return;
                state = stateSerializer.stateOf(pushStateData);
            }

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
                // resolveStatus 可能返回 RUNNING（中间状态），此时工作流已结束
                if (resolved == RdWorkflowStatus.RUNNING) {
                    status = RdWorkflowStatus.COMPLETED.name();
                } else {
                    status = resolved.name();
                }
            }

            log.info("pushFinalState: threadId={}, interrupted={}, status={}, review_feedback='{}'",
                    threadId, interrupted, status,
                    RdWorkflowKeys.extractStateText(state, RdWorkflowKeys.REVIEW_FEEDBACK, ""));

            // 更新元数据表最终状态
            WorkflowMetadata meta = new WorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setStatus(status);
            // COMPLETED 时将最终项目路径写入备注，方便在列表页直接查看项目存放位置
            if ("COMPLETED".equals(status)) {
                String projectRoot = RdWorkflowKeys.extractStateText(state,
                        CodeProjectWriteNode.CODE_PROJECT_ROOT, "");
                if (!projectRoot.isBlank()) {
                    meta.setRemark(projectRoot);
                }
                // 兜底补推：若之前各轮 push 都失败（网络/token 波动），
                // 工作流结束时再异步尝试一次，git 恢复后自动补齐
                if (gitService != null && !projectRoot.isBlank()) {
                    try {
                        gitService.pushAsync(java.nio.file.Path.of(projectRoot), threadId);
                    } catch (Exception e) {
                        log.warn("Git 兜底补推失败（不影响 COMPLETED）: {}", e.getMessage());
                    }
                }
            }
            updateMetadataStatus(meta);

            log.info("pushFinalState SSE keys: {}", state.data().keySet());
            for (Map.Entry<String, Object> entry : state.data().entrySet()) {
                String key = entry.getKey();
                if (RdWorkflowKeys.GENERATED_CODE.equals(key)) {
                    continue;
                }
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                eventBus.publish(threadId, key, value, "RUNNING");
            }
            // 仅 workflow_status 携带终态，供 SSE 端点识别并关闭连接
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS, status, status);

            // 清理临时项目目录，仅非 COMPLETED 时清理
            if (!"COMPLETED".equals(status)) {
                try {
                    CodeProjectWriter.cleanupForThread(threadId);
                } catch (Exception e) {
                    log.warn("清理临时目录失败: threadId={}", threadId, e);
                }
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
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, friendlyMsg, "RUNNING");
            log.info("异常已推送到SSE: threadId={}, msg={}", threadId, truncate(friendlyMsg, 50));
            WorkflowMetadata meta = new WorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setRemark(truncate(friendlyMsg, 300));
            meta.setStatus("FAILED");
            updateMetadataStatus(meta);
        } catch (Exception ex) {
            log.error("handleWorkflowException 内部失败: threadId={}", threadId, ex);
        }
    }

    private void pushErrorToSse(String threadId, Throwable e) {
        try {
            String friendlyMsg = toFriendlyErrorMessage(e);
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_MESSAGE, friendlyMsg, "RUNNING");
            WorkflowMetadata meta = new WorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setRemark(truncate(friendlyMsg, 300));
            meta.setStatus("FAILED");
            updateMetadataStatus(meta);
        } catch (Exception ex) {
            log.error("pushErrorToSse 内部失败: threadId={}", threadId, ex);
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
