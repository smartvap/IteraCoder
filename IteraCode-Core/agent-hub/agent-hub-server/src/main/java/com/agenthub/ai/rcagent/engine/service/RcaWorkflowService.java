package com.agenthub.ai.rcagent.engine.service;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.engine.config.RcaWorkflowGraphConfig;
import com.agenthub.ai.rcagent.engine.constant.RcaReviewDecision;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowStatus;
import com.agenthub.ai.rcagent.crosssource.support.CrossSourceStateSupport;
import com.agenthub.ai.rcagent.engine.dto.RcaSessionQueryDTO;
import com.agenthub.ai.rcagent.engine.dto.RcaWorkflowStartRequest;
import com.agenthub.ai.rcagent.engine.entity.RcaReviewAudit;
import com.agenthub.ai.rcagent.engine.entity.RcaWorkflowMetadata;
import com.agenthub.ai.rcagent.engine.mapper.RcaReviewAuditMapper;
import com.agenthub.ai.rcagent.engine.mapper.RcaWorkflowMetadataMapper;
import com.agenthub.ai.rcagent.engine.support.RcaSourceGroupService;
import com.agenthub.ai.rcagent.engine.vo.RcaWorkflowResultVO;
import com.agenthub.ai.rcagent.engine.vo.RcaSessionRecordVO;
import com.agenthub.ai.rcagent.engine.vo.RcaSourceResolveVO;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import reactor.core.Disposable;

/**
 * RCAgent 工作流服务：启动、人工审核恢复、状态查询、历史列表。
 * <p>
 * 基于 RdWorkflowService 骨架裁剪：删除 Docker 沙箱 / Git / harness 专属逻辑，
 * 增加数据源绑定（ds_id）、意图与风险快照、审核审计落库（rcagent_review_audit）。
 * 工作流执行在当前线程中通过 stream() + blockLast() 驱动，
 * 为防止阻塞 HTTP 线程，将实际执行提交到独立线程池。
 */
@Slf4j
@Service
public class RcaWorkflowService {

    /** doOnError/pushErrorToSse 推送的详细错误状态，供 pushFinalState 读取 */
    public static final Map<String, String> errorStatusDetails = new ConcurrentHashMap<>();

    /**
     * 跨线程 BLOCK 标记集合：BLOCK 收尾节点在 reactive 工作流线程中执行，
     * 而 pushFinalState 依赖的 {@code lastOutput.node()} 与 checkpoint {@code risk_level}
     * 在实测中均可能不可靠（收尾节点名非 rcagent_blocked_end、checkpoint 未落 BLOCK），
     * 导致 BLOCK 终态被误判为 COMPLETED。故以该静态并发集合作为
     * 「本 threadId 已发生 BLOCK」的跨线程可靠标记通道（与 errorStatusDetails 同风格）。
     * 由 {@link #consumeBlocked(String)} 消费一次即移除，避免残留污染同 threadId 的 resume/recover。
     */
    private static final Set<String> BLOCKED_THREADS = ConcurrentHashMap.newKeySet();

    /**
     * 标记指定 threadId 已被安全体系 BLOCK 拦截（由 rcagent_blocked_end 节点调用）。
     * threadId 为空时忽略，避免污染集合。
     */
    public static void markBlocked(String threadId) {
        if (threadId != null) {
            BLOCKED_THREADS.add(threadId);
        }
    }

    /**
     * 消费一次 BLOCK 标记（读取并移除）。返回 true 表示该 threadId 发生过 BLOCK。
     * threadId 为空返回 false。
     */
    public static boolean consumeBlocked(String threadId) {
        return threadId != null && BLOCKED_THREADS.remove(threadId);
    }

    /** BLOCK 拦截收尾节点名（与 RcaWorkflowGraphConfig 登记名一致；作为终态判据输入，不修改图配置） */
    private static final String RCA_BLOCKED_END_NODE = "rcagent_blocked_end";

    /** 风险分级 BLOCK（与 RcaValidateNode 写入 State 的值一致） */
    private static final String RISK_LEVEL_BLOCK = "BLOCK";

    /** BLOCK 场景 SSE 终态事件值（与 rcagent_blocked_end 节点已推 workflow_status 一致，DC-02） */
    private static final String BLOCKED_STATUS_VALUE = "FAILED — SQL 被安全体系拦截";

    /** BLOCK 场景可读失败原因（与 rcagent_blocked_end 节点 workflow_message 文案一致，FR-BE-008/009） */
    static final String BLOCKED_FAILED_REASON = "SQL 验证失败（BLOCK）：已拦截，不进入执行。请修改 SQL 后重试。";

    /** 活跃的工作流 reactive 流，用于手动终止 */
    private final Map<String, Disposable> activeStreams = new ConcurrentHashMap<>();

    private final CompiledGraph rcaWorkflowCompiledGraph;
    private final WorkflowEventBus eventBus;
    private final RcaWorkflowMetadataMapper metadataMapper;
    private final RcaReviewAuditMapper reviewAuditMapper;
    private final com.alibaba.cloud.ai.graph.serializer.StateSerializer stateSerializer;
    private final com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver rcaWorkflowSaver;
    /** 候选源解析服务（module-002 多源入口复用；容器外/单测构造时可为 null） */
    private final RcaSourceGroupService rcaSourceGroupService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @Value("${agenthub.rcagent.saver-type:memory}")
    private String saverType;

    /** 跨源源数上限（module-003，与 CrossSourceProperties.max-sources 同源；缺配置按 2 且不启动失败） */
    @Value("${agenthub.rcagent.cross-source.max-sources:2}")
    private int crossSourceMaxSources = 2;

    @org.springframework.beans.factory.annotation.Autowired
    public RcaWorkflowService(CompiledGraph rcaWorkflowCompiledGraph,
            WorkflowEventBus eventBus,
            RcaWorkflowMetadataMapper metadataMapper,
            RcaReviewAuditMapper reviewAuditMapper,
            com.alibaba.cloud.ai.graph.serializer.StateSerializer stateSerializer,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver rcaWorkflowSaver,
            @org.springframework.beans.factory.annotation.Autowired(required = false)
            RcaSourceGroupService rcaSourceGroupService) {
        this.rcaWorkflowCompiledGraph = rcaWorkflowCompiledGraph;
        this.eventBus = eventBus;
        this.metadataMapper = metadataMapper;
        this.reviewAuditMapper = reviewAuditMapper;
        this.stateSerializer = stateSerializer;
        this.rcaWorkflowSaver = rcaWorkflowSaver;
        this.rcaSourceGroupService = rcaSourceGroupService;
    }

    /**
     * 兼容既有 6 参构造（容器外/单测直接构造）：候选源解析服务缺省为空，
     * {@code start} 退化为既有单源行为，保证既有调用契约与单测零回归。
     */
    public RcaWorkflowService(CompiledGraph rcaWorkflowCompiledGraph,
            WorkflowEventBus eventBus,
            RcaWorkflowMetadataMapper metadataMapper,
            RcaReviewAuditMapper reviewAuditMapper,
            com.alibaba.cloud.ai.graph.serializer.StateSerializer stateSerializer,
            com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver rcaWorkflowSaver) {
        this(rcaWorkflowCompiledGraph, eventBus, metadataMapper, reviewAuditMapper,
                stateSerializer, rcaWorkflowSaver, null);
    }

    /**
     * 构建 RunnableConfig（threadId 通过 checkpoint saver 与 RcaWorkflowKeys.THREAD_ID_KEY 关联）。
     */
    private RunnableConfig buildConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    /**
     * 启动时清理遗留的 RUNNING 状态记录（服务重启前异常中断的流程）。
     * <ul>
     *   <li>memory saver：checkpoint 已随 JVM 丢失 → 标记 TERMINATED</li>
     *   <li>mysql/redis saver：checkpoint 持久化保留 → 保持 RUNNING，用户可手动终止或恢复</li>
     * </ul>
     */
    @jakarta.annotation.PostConstruct
    public void cleanStaleRunning() {
        if ("memory".equals(saverType)) {
            try {
                var wrapper = new LambdaQueryWrapper<RcaWorkflowMetadata>()
                        .eq(RcaWorkflowMetadata::getStatus, "RUNNING");
                var stale = metadataMapper.selectList(wrapper);
                for (RcaWorkflowMetadata meta : stale) {
                    String threadId = meta.getThreadId();
                    meta.setStatus("TERMINATED");
                    meta.setRemark("系统自动终止");
                    updateMetadataStatus(meta);
                    try {
                        RunnableConfig config = buildConfig(threadId);
                        Map<String, Object> stateUpdate = new HashMap<>();
                        stateUpdate.put(RcaWorkflowKeys.WORKFLOW_STATUS, "TERMINATED");
                        stateUpdate.put(RcaWorkflowKeys.WORKFLOW_MESSAGE, "系统自动终止");
                        rcaWorkflowCompiledGraph.updateState(config, stateUpdate,
                                RcaWorkflowGraphConfig.RCA_REVIEW_NODE);
                    } catch (Exception e) {
                        log.warn("清理 checkpoint 失败（可能已丢失）: threadId={}", threadId, e);
                    }
                }
                if (!stale.isEmpty()) {
                    log.info("RCAgent 启动清理(memory)：{} 条 RUNNING 记录已标记为 TERMINATED", stale.size());
                }
            } catch (Exception e) {
                log.warn("RCAgent 启动清理遗留 RUNNING 记录失败", e);
            }
        } else {
            log.info("RCAgent saver-type={}，跳过自动终止，RUNNING 记录保留（用户可从页面手动操作）", saverType);
        }
    }

    /**
     * 启动 RCAgent 会话（异步，单源向后兼容入口）：提交问题+数据源，立即返回 threadId。
     * <p>委托至多源入口 {@link #start(String, String, String, List)}。</p>
     */
    public RcaWorkflowResultVO start(String question, String dsId) {
        return start(question, dsId, null, null);
    }

    /**
     * 启动 RCAgent 会话（异步，多源入口）：提交问题 + 查询范围（单源 dsId / 系统 systemCode / 显式子集 dsIds），
     * 服务端解析候选源集合并校验后异步启动，立即返回 threadId 与本次覆盖的候选源集合。
     *
     * <p>解析与校验（存在性/同组/上限/ACTIVE）统一由 {@link RcaSourceGroupService#resolveCandidates} 完成；
     * 校验失败抛 40000，**不生成 threadId、不写元数据表、不启动工作流**。候选集合 ≤1 时静默退化为单源
     * （{@code DS_IDS=[DS_ID]}），全链路与现状一致（零回归）。</p>
     */
    public RcaWorkflowResultVO start(String question, String dsId, String systemCode, List<String> dsIds) {
        RcaWorkflowStartRequest request = new RcaWorkflowStartRequest();
        request.setQuestion(question);
        request.setDsId(dsId);
        request.setSystemCode(systemCode);
        request.setDsIds(dsIds);
        return start(request);
    }

    /**
     * 启动 RCAgent 会话（异步，兼容跨源 join 兜底入口）：在既有查询范围之外，追加**可选**的
     * 用户显式 join 指定（{@code joinType}/{@code leftDsId}/{@code rightDsId}/{@code leftJoinKey}/
     * {@code rightJoinKey}）。
     *
     * <p>跨源字段校验与 {@code JOIN_HINT} 装配由
     * {@link CrossSourceStateSupport#normalizeAndValidateJoinHint} 统一完成（api-contract §2.2 逐条）：
     * 任一 join 字段非空时要求候选源恰为 2 个，校验失败抛 40000 且不产生任何副作用；
     * 全部缺省时**不写** {@code JOIN_HINT}（单源零回归）。</p>
     */
    public RcaWorkflowResultVO start(RcaWorkflowStartRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数不能为空");
        }
        String question = request.getQuestion();
        String dsId = request.getDsId();
        String systemCode = request.getSystemCode();
        List<String> dsIds = request.getDsIds();
        if (question == null || question.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "提问内容不能为空");
        }
        if (question.length() > 4000) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "提问内容过长（≤4000）");
        }

        String primaryDsId;
        String effectiveSystemCode;
        List<String> effectiveDsIds;
        if (rcaSourceGroupService != null) {
            RcaSourceResolveVO resolved = rcaSourceGroupService.resolveCandidates(dsId, systemCode, dsIds);
            primaryDsId = resolved.getPrimaryDsId();
            effectiveSystemCode = resolved.getSystemCode();
            effectiveDsIds = resolved.getDsIds();
        } else {
            // 兼容容器外/单测直接构造（未注入解析服务）：退化为既有单源行为，既有调用契约零回归。
            if (dsId == null || dsId.isBlank()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "请先选择数据源（ds_id）");
            }
            if (dsId.length() > 64) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "数据源 ID 过长");
            }
            primaryDsId = dsId.trim();
            effectiveSystemCode = null;
            effectiveDsIds = List.of(primaryDsId);
        }

        // 跨源 join 兜底入口（module-003，api-contract §2.2/§3.1）：校验 + 归一化；全空返回空串（不写键）
        String joinHint = CrossSourceStateSupport.normalizeAndValidateJoinHint(
                request.getJoinType(), request.getLeftDsId(), request.getRightDsId(),
                request.getLeftJoinKey(), request.getRightJoinKey(),
                effectiveDsIds, crossSourceMaxSources);

        String threadId = UUID.randomUUID().toString().replace("-", "");
        // 清理可能的残留 BLOCK 标记（threadId 为新生成，正常无残留；防御性清理，避免复用场景污染终态）
        BLOCKED_THREADS.remove(threadId);
        Map<String, Object> input = new HashMap<>();
        input.put(RcaWorkflowKeys.QUESTION, question.trim());
        input.put(RcaWorkflowKeys.DS_ID, primaryDsId);
        input.put(RcaWorkflowKeys.DS_IDS, effectiveDsIds);
        if (effectiveSystemCode != null) {
            input.put(RcaWorkflowKeys.SYSTEM_CODE, effectiveSystemCode);
        }
        if (!joinHint.isBlank()) {
            // 仅当用户传了任一跨源字段时写入（内部键：不进 SSE、/state 脱敏占位）
            input.put(RcaWorkflowKeys.JOIN_HINT, joinHint);
        }
        input.put(RcaWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
        input.put(RcaWorkflowKeys.WORKFLOW_MESSAGE, "RCAgent 会话已启动");
        // 供 MultiRoundAgentNode / 节点推送 SSE 事件使用
        input.put(com.agenthub.ai.workflow.node.MultiRoundAgentNode.THREAD_ID_KEY, threadId);
        RunnableConfig config = buildConfig(threadId);

        // 写入元数据表（零 DDL：ds_id 保持主源语义，不新增 system_code/ds_ids 列）
        RcaWorkflowMetadata meta = new RcaWorkflowMetadata();
        meta.setThreadId(threadId);
        meta.setDsId(primaryDsId);
        meta.setQuestion(question.trim());
        meta.setStatus("RUNNING");
        meta.setCreatorId(effectiveCreatorId());
        meta.setCreateTime(new Date());
        meta.setUpdateTime(new Date());
        metadataMapper.insert(meta);

        // 异步执行工作流，不阻塞 HTTP 线程
        CompletableFuture.runAsync(() -> {
            AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
            Disposable disposable = rcaWorkflowCompiledGraph.stream(input, config)
                    .subscribe(
                            lastOutput::set,
                            e -> {
                                activeStreams.remove(threadId);
                                pushErrorToSse(threadId, e);
                                log.error("RCAgent 工作流执行异常: threadId={}", threadId, e);
                                handleWorkflowException(threadId, config,
                                        e instanceof Exception ex ? ex : new RuntimeException(e));
                            },
                            () -> {
                                activeStreams.remove(threadId);
                                pushFinalState(config, lastOutput.get());
                                log.info("RCAgent 工作流执行完成: threadId={}", threadId);
                            }
                    );
            activeStreams.put(threadId, disposable);
        }, executor);

        log.info("RCAgent 会话已启动（异步）: threadId={}, dsId={}, sourceCount={}",
                threadId, primaryDsId, effectiveDsIds.size());
        return RcaWorkflowResultVO.builder()
                .threadId(threadId)
                .status(RcaWorkflowStatus.RUNNING)
                .message("RCAgent 会话已启动，正在执行中...")
                .systemCode(effectiveSystemCode)
                .dsIds(effectiveDsIds)
                .build();
    }

    /**
     * 人工审核后恢复会话（异步）。
     * TERMINATED 决策同步返回，APPROVED/SENT_BACK 异步执行续跑。
     */
    public RcaWorkflowResultVO resume(String threadId, RcaReviewDecision decision, String comment) {
        if (threadId == null || threadId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "threadId 不能为空");
        }
        if (decision == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "审核决策不能为空");
        }
        if (comment != null && comment.length() > 1024) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "审核意见过长（≤1024）");
        }
        RunnableConfig config = buildConfig(threadId);
        // 清理上一轮残留的 BLOCK 标记（同一 threadId 复用：resume 续跑/打回重生成不应继承旧 BLOCK 终态）
        BLOCKED_THREADS.remove(threadId);

        try {
            // 会话主体（审核审计/元数据更新复用）；resume 前先按 metadata 校验会话存在（对齐 recover 的 40400）
            RcaWorkflowMetadata meta = metadataMapper.selectById(threadId);

            // 前置检查：终态不允许恢复
            Map<String, Object> resumeStateData = queryLatestState(threadId);
            OverAllState resumeState = (resumeStateData != null)
                    ? stateSerializer.stateOf(resumeStateData) : null;
            if (resumeState == null && meta == null) {
                // 会话不存在（含 start 后 checkpoint 尚未创建且无元数据主体）：返回 40400，避免落无主审核审计（修复审查问题 5）
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
            }
            if (resumeState != null) {
                String existingDecision = RcaWorkflowKeys.extractStateText(
                        resumeState, RcaWorkflowKeys.REVIEW_DECISION, "");
                if ("TERMINATED".equals(existingDecision)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "会话已终止，无法再次操作");
                }
                String status = RcaWorkflowKeys.extractStateText(
                        resumeState, RcaWorkflowKeys.WORKFLOW_STATUS, "");
                if ("COMPLETED".equals(status)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "会话已完成，无法再次操作");
                }
            }

            Map<String, Object> stateUpdate = new HashMap<>();
            stateUpdate.put(RcaWorkflowKeys.REVIEW_DECISION, decision.name());
            stateUpdate.put(RcaWorkflowKeys.REVIEW_FEEDBACK,
                    (comment != null && !comment.isBlank()) ? comment : "");
            stateUpdate.put(com.agenthub.ai.workflow.node.MultiRoundAgentNode.THREAD_ID_KEY, threadId);

            // TERMINATED / SENT_BACK / APPROVED 分别设置 workflow_status。
            // SENT_BACK 先落 SENT_BACK（六态之一真正落库/入 checkpoint，使 /state、/sessions 可观测「已打回」，修复审查问题 3），
            // 异步流真正从 sql_generate 恢复执行后由 SQL 生成节点回写 RUNNING。
            String updateStatus;
            String updateMessage;
            if (RcaReviewDecision.TERMINATED.equals(decision)) {
                updateStatus = "TERMINATED";
                updateMessage = (comment != null && !comment.isBlank()) ? comment : "会话已终止";
                stateUpdate.put(RcaWorkflowKeys.WORKFLOW_STATUS, "TERMINATED");
                stateUpdate.put(RcaWorkflowKeys.WORKFLOW_MESSAGE, updateMessage);
            } else if (RcaReviewDecision.SENT_BACK.equals(decision)) {
                updateStatus = "SENT_BACK";
                updateMessage = (comment != null && !comment.isBlank()) ? comment : "审核已打回，重新生成 SQL";
                stateUpdate.put(RcaWorkflowKeys.WORKFLOW_STATUS, "SENT_BACK");
                stateUpdate.put(RcaWorkflowKeys.WORKFLOW_MESSAGE, updateMessage);
            } else {
                updateStatus = "RUNNING";
                updateMessage = "";
                stateUpdate.put(RcaWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
                stateUpdate.put(RcaWorkflowKeys.WORKFLOW_MESSAGE, "");
            }

            // 更新元数据表（SENT_BACK 决策先落 SENT_BACK，使 /sessions 等历史可查询「已打回」）
            RcaWorkflowMetadata metaUpdate = new RcaWorkflowMetadata();
            metaUpdate.setThreadId(threadId);
            metaUpdate.setReviewDecision(decision.name());
            metaUpdate.setReviewFeedback(comment != null ? comment : "");
            metaUpdate.setStatus(updateStatus);
            if (RcaReviewDecision.TERMINATED.equals(decision) && meta != null) {
                metaUpdate.setRemark("人工审核拒绝：" + (comment != null && !comment.isBlank() ? comment : "无意见"));
            }
            if (RcaReviewDecision.SENT_BACK.equals(decision) && meta != null && !updateMessage.isBlank()) {
                metaUpdate.setRemark("审核打回：" + updateMessage);
            }
            updateMetadataStatus(metaUpdate);

            // 所有恢复操作清空 SSE 旧事件 + 递增 epoch
            eventBus.clearEvents(threadId);

            // SENT_BACK：清空 SQL 后续残留（回退到 sql_generate 重新生成）；
            // module-003 追加清空逐源执行摘要与跨源说明（join_plan/source_query_plans 保留，供重生成参考；
            // 单源会话上述键缺失 ⇒ put(null) 不改变既有语义）。
            if (RcaReviewDecision.SENT_BACK.equals(decision)) {
                stateUpdate.put(RcaWorkflowKeys.REVIEW_CONTENT, null);
                stateUpdate.put(RcaWorkflowKeys.SQL_VALIDATE_REPORT, null);
                stateUpdate.put(RcaWorkflowKeys.RISK_LEVEL, null);
                stateUpdate.put(RcaWorkflowKeys.QUERY_RESULT, null);
                stateUpdate.put(RcaWorkflowKeys.ATTRIBUTION_RESULT, null);
                stateUpdate.put(RcaWorkflowKeys.RESULT_SUMMARY, null);
                stateUpdate.put(RcaWorkflowKeys.SOURCE_QUERY_RESULTS, null);
                stateUpdate.put(RcaWorkflowKeys.CROSS_SOURCE_NOTE, null);
            }

            RunnableConfig updatedConfig = rcaWorkflowCompiledGraph.updateState(
                    config, stateUpdate, RcaWorkflowGraphConfig.RCA_REVIEW_NODE);

            // 审核审计落库延后到 updateState 成功之后，避免失败/无主会话留下脏审计（修复审查问题 5）
            RcaReviewAudit audit = new RcaReviewAudit();
            audit.setThreadId(threadId);
            audit.setReviewObject("SQL");
            if (meta != null) {
                audit.setRiskLevel(meta.getRiskLevel());
            }
            if (resumeState != null) {
                // module-003：跨源会话审核审计取逐源 SQL（截断 65535）；单源会话该键缺失 ⇒ 回退 sql_generate（现状）
                String crossPlans = RcaWorkflowKeys.extractStateText(
                        resumeState, RcaWorkflowKeys.SOURCE_QUERY_PLANS, "");
                audit.setSqlDraft(crossPlans.isBlank()
                        ? truncate(RcaWorkflowKeys.extractStateText(
                                resumeState, RcaWorkflowKeys.SQL_GENERATE, ""), 65535)
                        : truncate(crossPlans, 65535));
            }
            audit.setDecision(decision.name());
            audit.setComment(comment != null && comment.length() > 1024
                    ? comment.substring(0, 1024) : comment);
            audit.setReviewerId(BaseContext.getCurrentId());
            audit.setCreateTime(new Date());
            reviewAuditMapper.insert(audit);

            // TERMINATED 同步返回
            if (RcaReviewDecision.TERMINATED.equals(decision)) {
                OverAllState state = rcaWorkflowCompiledGraph.getState(updatedConfig).state();
                String msg = (comment != null && !comment.isBlank()) ? comment : "会话已终止";
                return RcaWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RcaWorkflowStatus.TERMINATED)
                        .interrupted(false)
                        .state(buildPublicState(state.data(), false))
                        .message(msg)
                        .build();
            }

            // APPROVED / SENT_BACK：异步恢复执行
            final RunnableConfig resumeConfig = updatedConfig;
            CompletableFuture.runAsync(() -> {
                AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
                Disposable disposable = rcaWorkflowCompiledGraph.stream(null, resumeConfig)
                        .subscribe(
                                lastOutput::set,
                                e -> {
                                    activeStreams.remove(threadId);
                                    log.error("RCAgent 恢复流程异常", e);
                                    pushErrorToSse(threadId, e);
                                    handleWorkflowException(threadId, resumeConfig,
                                            e instanceof Exception ex ? ex : new RuntimeException(e));
                                },
                                () -> {
                                    activeStreams.remove(threadId);
                                    pushFinalState(resumeConfig, lastOutput.get());
                                    log.info("RCAgent 恢复执行完成: threadId={}", threadId);
                                }
                        );
                activeStreams.put(threadId, disposable);
            }, executor);

            return RcaWorkflowResultVO.builder()
                    .threadId(threadId)
                    .status(RcaWorkflowStatus.RUNNING)
                    .message("审核决策已提交，会话继续执行...")
                    .build();
        } catch (Exception e) {
            log.error("RCAgent 会话恢复失败, threadId={}, decision={}", threadId, decision, e);
            throw e instanceof BusinessException ? (BusinessException) e
                    : new BusinessException(ErrorCode.OPERATION_ERROR, "会话恢复失败: " + e.getMessage());
        }
    }

    /**
     * 从 checkpoint 恢复执行（服务重启 / FAILED 重试）。
     * 不含 rd 的沙箱/Git 预检；WAITING_REVIEW 状态禁止 recover。
     */
    public RcaWorkflowResultVO recover(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "threadId 不能为空");
        }
        RcaWorkflowMetadata meta = metadataMapper.selectById(threadId);
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }

        // 防重：已有活跃流执行中，不允许重复恢复；FAILED 残留允许清除后恢复
        if (activeStreams.containsKey(threadId)) {
            if ("FAILED".equals(meta.getStatus())) {
                log.warn("RCAgent recover: 清除 activeStreams 残留条目（status=FAILED，允许恢复）, threadId={}", threadId);
                activeStreams.remove(threadId);
            } else {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "该会话正在执行中，请勿重复恢复");
            }
        }
        errorStatusDetails.remove(threadId);
        // 同上：清理残留 BLOCK 标记，避免恢复后终态被旧标记污染
        BLOCKED_THREADS.remove(threadId);

        RunnableConfig config = buildConfig(threadId);

        try {
            Map<String, Object> recoverStateData = queryLatestState(threadId);
            String resumeNode = null;
            if (recoverStateData != null) {
                OverAllState state = stateSerializer.stateOf(recoverStateData);
                String workflowStatus = RcaWorkflowKeys.extractStateText(
                        state, RcaWorkflowKeys.WORKFLOW_STATUS, "");
                if ("WAITING_REVIEW".equals(workflowStatus)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "该会话当前处于等待人工审核状态，请通过审核界面操作");
                }
                resumeNode = determineResumeNode(state);
                if (resumeNode != null) {
                    log.info("RCAgent [RECOVER] 定位续跑点: {}, threadId={}", resumeNode, threadId);
                    Map<String, Object> clearState = new HashMap<>();
                    clearState.put(RcaWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
                    clearState.put(RcaWorkflowKeys.WORKFLOW_MESSAGE, "从 checkpoint 恢复");
                    config = rcaWorkflowCompiledGraph.updateState(config, clearState, resumeNode);
                    // 重置 checkpoint.nextNodeId（updateState 只更新 state 数据）
                    final String resetResumeNode = resumeNode;
                    final RunnableConfig resetConfig = config;
                    if (rcaWorkflowSaver != null) {
                        try {
                            rcaWorkflowSaver.get(resetConfig).ifPresent(cp -> {
                                try {
                                    com.alibaba.cloud.ai.graph.checkpoint.Checkpoint newCp =
                                            com.alibaba.cloud.ai.graph.checkpoint.Checkpoint.builder()
                                                    .id(cp.getId())
                                                    .state(cp.getState())
                                                    .nodeId(cp.getNodeId())
                                                    .nextNodeId(resetResumeNode)
                                                    .build();
                                    rcaWorkflowSaver.put(resetConfig, newCp);
                                    log.info("RCAgent [RECOVER] 重置 checkpoint.nextNodeId: {} → {} (threadId={})",
                                            cp.getNextNodeId(), resetResumeNode, threadId);
                                } catch (Exception ex) {
                                    log.warn("RCAgent [RECOVER] 重置 checkpoint.nextNodeId 失败: {}", ex.getMessage());
                                }
                            });
                        } catch (Exception ex) {
                            log.warn("RCAgent [RECOVER] 读取 checkpoint 失败: {}", ex.getMessage());
                        }
                    }
                } else {
                    log.info("RCAgent [RECOVER] 无法定位续跑点，从头执行: threadId={}", threadId);
                }
            }

            // 更新元数据
            RcaWorkflowMetadata metaUpdate = new RcaWorkflowMetadata();
            metaUpdate.setThreadId(threadId);
            metaUpdate.setStatus("RUNNING");
            metaUpdate.setRemark("从 checkpoint 恢复");
            updateMetadataStatus(metaUpdate);

            // 清空旧 SSE 事件
            eventBus.clearEvents(threadId);

            // 异步恢复执行
            final RunnableConfig recoverConfig = config;
            CompletableFuture.runAsync(() -> {
                AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
                Disposable disposable = rcaWorkflowCompiledGraph.stream(null, recoverConfig)
                        .subscribe(
                                lastOutput::set,
                                e -> {
                                    activeStreams.remove(threadId);
                                    pushErrorToSse(threadId, e);
                                    log.error("RCAgent 恢复执行异常: threadId={}", threadId, e);
                                    handleWorkflowException(threadId, recoverConfig,
                                            e instanceof Exception ex ? ex : new RuntimeException(e));
                                },
                                () -> {
                                    activeStreams.remove(threadId);
                                    pushFinalState(recoverConfig, lastOutput.get());
                                    log.info("RCAgent 恢复执行完成: threadId={}", threadId);
                                }
                        );
                activeStreams.put(threadId, disposable);
            }, executor);

            log.info("RCAgent 会话已恢复执行: threadId={}", threadId);
            return RcaWorkflowResultVO.builder()
                    .threadId(threadId)
                    .status(RcaWorkflowStatus.RUNNING)
                    .message("会话已从 checkpoint 恢复执行")
                    .build();
        } catch (Exception e) {
            activeStreams.remove(threadId);
            log.error("RCAgent 恢复执行失败: threadId={}", threadId, e);
            throw e instanceof BusinessException ? (BusinessException) e
                    : new BusinessException(ErrorCode.OPERATION_ERROR, "恢复执行失败: " + e.getMessage());
        }
    }

    /**
     * 根据 checkpoint state 判断工作流中断位置，返回应续跑的节点名。
     * 判断顺序与图执行顺序相反：越靠后越精确。
     */
    private String determineResumeNode(OverAllState state) {
        // module-003：跨源会话续跑位置（前置判定）。前置条件为「DS_IDS.size()>1 且 存在跨源内部键」，
        // 单源会话恒不成立 ⇒ 后续判定顺序与结果不变（零回归）。
        if (CrossSourceStateSupport.isCrossSourceResume(state)) {
            if (hasStateValue(state, RcaWorkflowKeys.SOURCE_QUERY_RESULTS)) {
                return RcaWorkflowGraphConfig.RCA_CROSS_SOURCE_EXECUTE_NODE;
            }
            if (hasStateValue(state, RcaWorkflowKeys.SOURCE_QUERY_PLANS)
                    || hasStateValue(state, RcaWorkflowKeys.JOIN_PLAN)) {
                return RcaWorkflowGraphConfig.RCA_CROSS_SOURCE_GENERATE_NODE;
            }
        }
        // 归因结论已产出 → result_check 之后；走 attribution 衔接或收尾（此处直接执行末端无后续节点 → 走 result_check 判定终态）
        if (hasStateValue(state, RcaWorkflowKeys.ATTRIBUTION_RESULT)
                || hasStateValue(state, RcaWorkflowKeys.QUERY_RESULT)) {
            return RcaWorkflowGraphConfig.RCA_EXECUTE_NODE;
        }
        String decision = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.REVIEW_DECISION, "");
        if ("APPROVED".equals(decision)) {
            return RcaWorkflowGraphConfig.RCA_EXECUTE_NODE;
        }
        if (hasStateValue(state, RcaWorkflowKeys.RISK_LEVEL)) {
            String risk = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RISK_LEVEL, "");
            if ("LOW".equals(risk)) {
                return RcaWorkflowGraphConfig.RCA_EXECUTE_NODE;
            }
            // MEDIUM/HIGH：已审核放行（review_decision=APPROVED 已被上方处理）
            return RcaWorkflowGraphConfig.RCA_EXECUTE_NODE;
        }
        if (hasStateValue(state, RcaWorkflowKeys.SQL_GENERATE)) {
            return RcaWorkflowGraphConfig.RCA_VALIDATE_NODE;
        }
        if (hasStateValue(state, RcaWorkflowKeys.SQL_PLAN)) {
            return RcaWorkflowGraphConfig.RCA_SQL_GENERATE_NODE;
        }
        if (hasStateValue(state, RcaWorkflowKeys.RETRIEVAL_CONTEXT)) {
            return RcaWorkflowGraphConfig.RCA_SQL_PLAN_NODE;
        }
        if (hasStateValue(state, RcaWorkflowKeys.INTENT_RESULT)
                || hasStateValue(state, RcaWorkflowKeys.INTENT_TYPE)) {
            return RcaWorkflowGraphConfig.RCA_RETRIEVAL_NODE;
        }
        return RcaWorkflowGraphConfig.RCA_INTENT_NODE;
    }

    private static boolean hasStateValue(OverAllState state, String key) {
        return state.value(key).map(v -> {
            if (v instanceof String s) return !s.isBlank();
            return true;
        }).orElse(false);
    }

    /**
     * 查询会话当前状态（支持 RUNNING 进度查询 + checkpoint 未写入时的兜底）。
     */
    public RcaWorkflowResultVO getState(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "threadId 不能为空");
        }
        try {
            Map<String, Object> stateData = queryLatestState(threadId);
            if (stateData == null) {
                return RcaWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RcaWorkflowStatus.RUNNING)
                        .message("会话正在初始化中...")
                        .build();
            }
            OverAllState state = stateSerializer.stateOf(stateData);

            RcaWorkflowStatus status = resolveStatus(state, false);

            // 元数据表兜底：重启后 checkpoint 可能缺失终态标记
            RcaWorkflowMetadata metadata = metadataMapper.selectById(threadId);
            if (metadata != null && isTerminalStatus(metadata.getStatus())) {
                status = RcaWorkflowStatus.valueOf(metadata.getStatus());
            } else if (metadata != null && "WAITING_REVIEW".equals(metadata.getStatus())) {
                status = RcaWorkflowStatus.WAITING_REVIEW;
            }

            String message = RcaWorkflowKeys.extractStateText(
                    state, RcaWorkflowKeys.WORKFLOW_MESSAGE, "");
            if (status == RcaWorkflowStatus.RUNNING && message.isBlank()) {
                message = "会话执行中...";
            } else if (status == RcaWorkflowStatus.FAILED || status == RcaWorkflowStatus.TERMINATED) {
                if (metadata != null && metadata.getRemark() != null && !metadata.getRemark().isBlank()) {
                    message = metadata.getRemark();
                } else if (message.isBlank()) {
                    message = status == RcaWorkflowStatus.FAILED ? "会话执行失败" : "会话已终止";
                }
            } else if (status == RcaWorkflowStatus.WAITING_REVIEW) {
                message = "会话已暂停，等待人工审核 SQL 与归因结论";
            } else if (status == RcaWorkflowStatus.SENT_BACK && message.isBlank()) {
                message = "会话已打回，正在按审核意见重新生成 SQL";
            } else if (status == RcaWorkflowStatus.COMPLETED && message.isBlank()) {
                message = "会话执行完成";
            }
            // 审核敏感字段输出权限（占位鉴权阶段，修复审查问题 1）：
            //  - SQL 草案(sql_generate)一律脱敏，全文仅审核通道/审核审计可见；
            //  - 审核内容(review_content)仅 WAITING_REVIEW 审核态返回供审核面板，其余状态剥离为占位。
            // 真实 RBAC 落地后在此按 40101 权限点放行审核人/拒绝越权者（TODO: 与鉴权模块对齐）。
            Map<String, Object> sanitized = buildPublicState(state.data(),
                    status == RcaWorkflowStatus.WAITING_REVIEW);

            // 多源可观测（FR-BE-008）：从 State 读取 SYSTEM_CODE / DS_IDS；DS_IDS 缺失（历史 checkpoint）回落 [DS_ID]
            String stateSystemCode = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.SYSTEM_CODE, "");
            List<String> stateDsIds = extractDsIds(state);

            return RcaWorkflowResultVO.builder()
                    .threadId(threadId)
                    .status(status)
                    .interrupted(status == RcaWorkflowStatus.WAITING_REVIEW)
                    .interruptedNode(status == RcaWorkflowStatus.WAITING_REVIEW
                            ? RcaWorkflowGraphConfig.RCA_REVIEW_NODE : null)
                    .state(sanitized)
                    .message(message)
                    .systemCode(stateSystemCode.isBlank() ? null : stateSystemCode)
                    .dsIds(stateDsIds)
                    .build();
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("Missing Checkpoint")) {
                log.debug("RCAgent checkpoint 尚未就绪: threadId={}", threadId);
                return RcaWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RcaWorkflowStatus.RUNNING)
                        .message("会话正在初始化中...")
                        .build();
            }
            throw e;
        } catch (Exception e) {
            log.error("查询会话状态失败, threadId={}", threadId, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "查询会话状态失败: " + e.getMessage());
        }
    }

    /**
     * 从 State 安全提取候选源集合（兼容历史 checkpoint 与不同序列化形态）。
     * <p>支持 List / 数组 / 逗号或中括号字符串；缺失或为空时回落为单元素 [DS_ID]（FR-BE-008）。</p>
     */
    private List<String> extractDsIds(OverAllState state) {
        List<String> ids = new ArrayList<>();
        Object value = state.value(RcaWorkflowKeys.DS_IDS).orElse(null);
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                addIfPresent(ids, item);
            }
        } else if (value instanceof Object[] array) {
            for (Object item : array) {
                addIfPresent(ids, item);
            }
        } else if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
            for (String part : trimmed.split(",")) {
                addIfPresent(ids, part.replace("\"", ""));
            }
        }
        if (ids.isEmpty()) {
            String single = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.DS_ID, "");
            if (!single.isBlank()) {
                ids.add(single);
            }
        }
        return ids;
    }

    private static void addIfPresent(List<String> target, Object item) {
        if (item == null) {
            return;
        }
        String text = item.toString().trim();
        if (!text.isEmpty()) {
            target.add(text);
        }
    }

    /**
     * 手动终止会话：metadata 置 TERMINATED、checkpoint 写 TERMINATED、切断活跃流、SSE 推送终态。
     */
    public void terminateWorkflow(String threadId) {
        RcaWorkflowMetadata meta = metadataMapper.selectById(threadId);
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        if (isTerminalStatus(meta.getStatus())) {
            log.info("RCAgent terminate 幂等：会话已处于终态 {}, threadId={}", meta.getStatus(), threadId);
            return;
        }
        // 1. 更新元数据表
        meta.setStatus("TERMINATED");
        meta.setRemark("手动终止");
        updateMetadataStatus(meta);

        // 2. 更新 graph checkpoint
        RunnableConfig config = buildConfig(threadId);
        try {
            Map<String, Object> stateUpdate = new HashMap<>();
            stateUpdate.put(RcaWorkflowKeys.WORKFLOW_STATUS, "TERMINATED");
            stateUpdate.put(RcaWorkflowKeys.WORKFLOW_MESSAGE, "手动终止");
            rcaWorkflowCompiledGraph.updateState(config, stateUpdate,
                    RcaWorkflowGraphConfig.RCA_REVIEW_NODE);
            log.info("RCAgent checkpoint 已更新为 TERMINATED: threadId={}", threadId);
        } catch (Exception e) {
            log.warn("RCAgent 更新 checkpoint 失败（流程可能尚未创建 checkpoint）: threadId={}", threadId, e);
        }

        // 3. 切断运行中的 reactive 流
        Disposable disposable = activeStreams.remove(threadId);
        boolean wasActive = (disposable != null);
        if (wasActive && !disposable.isDisposed()) {
            disposable.dispose();
            log.info("RCAgent 已切断 reactive 流: threadId={}", threadId);
        }

        // 4. 推送最终状态到 SSE
        if (wasActive) {
            try {
                pushFinalState(config, null);
            } catch (Exception e) {
                log.warn("RCAgent 推送终止状态失败: threadId={}", threadId, e);
            }
        }
        log.info("RCAgent 手动终止完成: threadId={}", threadId);
    }

    /**
     * 查询历史会话列表（基于 rcagent_workflow_metadata 文本字段，不读 checkpoint）。
     */
    public PageResult listRecords(RcaSessionQueryDTO query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "查询条件不能为空");
        }
        LambdaQueryWrapper<RcaWorkflowMetadata> wrapper = new LambdaQueryWrapper<>();
        if (query.getStartDate() != null && !query.getStartDate().isBlank()) {
            wrapper.ge(RcaWorkflowMetadata::getCreateTime, query.getStartDate() + " 00:00:00");
        }
        if (query.getEndDate() != null && !query.getEndDate().isBlank()) {
            wrapper.le(RcaWorkflowMetadata::getCreateTime, query.getEndDate() + " 23:59:59");
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()) {
            wrapper.eq(RcaWorkflowMetadata::getStatus, query.getStatus());
        }
        if (query.getDsId() != null && !query.getDsId().isBlank()) {
            wrapper.eq(RcaWorkflowMetadata::getDsId, query.getDsId());
        }
        if (query.getQuestion() != null && !query.getQuestion().isBlank()) {
            wrapper.like(RcaWorkflowMetadata::getQuestion, query.getQuestion());
        }
        if (query.getThreadId() != null && !query.getThreadId().isBlank()) {
            wrapper.like(RcaWorkflowMetadata::getThreadId, query.getThreadId());
        }
        wrapper.orderByDesc(RcaWorkflowMetadata::getCreateTime);

        int page = query.getPage() != null && query.getPage() > 0 ? query.getPage() : 1;
        int pageSize = query.getPageSize() != null && query.getPageSize() > 0 ? query.getPageSize() : 15;

        // 分页由 MP PaginationInnerInterceptor 提供：BaseMapper#selectPage 显式传入 IPage，SQL 本身不写 limit
        IPage<RcaWorkflowMetadata> pageResult = metadataMapper.selectPage(new Page<>(page, pageSize), wrapper);

        List<RcaSessionRecordVO> records = pageResult.getRecords().stream()
                .map(r -> RcaSessionRecordVO.builder()
                        .threadId(r.getThreadId())
                        .dsId(r.getDsId())
                        .question(r.getQuestion())
                        .intentType(r.getIntentType())
                        .status(r.getStatus())
                        .riskLevel(r.getRiskLevel())
                        .reviewDecision(r.getReviewDecision())
                        .resultSummary(r.getResultSummary())
                        .remark(r.getRemark())
                        .createTime(r.getCreateTime())
                        .updateTime(r.getUpdateTime())
                        .build())
                .toList();
        return new PageResult(pageResult.getTotal(), records);
    }

    /**
     * 记录结果反馈（P0 先记录到 metadata.remark；转发 feedback-loop 由依赖模块占位契约后续对齐）。
     */
    public String recordFeedback(String threadId, String feedbackType, String comment) {
        if (threadId == null || threadId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "threadId 不能为空");
        }
        if (feedbackType == null || feedbackType.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "feedbackType 不能为空");
        }
        if (!List.of("USEFUL", "USELESS", "CORRECTION").contains(feedbackType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "feedbackType 非法");
        }
        if (comment != null && comment.length() > 2000) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "反馈内容过长（≤2000）");
        }
        RcaWorkflowMetadata meta = metadataMapper.selectById(threadId);
        if (meta == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "会话不存在");
        }
        String remark = meta.getRemark();
        String newFeedback = "反馈[" + feedbackType + "]: "
                + (comment != null && !comment.isBlank() ? truncate(comment, 300) : "无文本");
        meta.setRemark(remark == null || remark.isBlank()
                ? newFeedback : truncate(remark, 450) + " | " + newFeedback);
        meta.setUpdateTime(new Date());
        metadataMapper.updateById(meta);
        log.info("RCAgent 反馈已记录: threadId={}, feedbackType={}", threadId, feedbackType);
        // TODO 转发 feedback-loop（依赖模块契约占位）：待 module-008 接口契约确定后在此对齐
        return "反馈已记录";
    }

    // ============ 收尾辅助 ============

    private void pushFinalState(RunnableConfig config, NodeOutput lastOutput) {
        try {
            String threadId = config.threadId().orElse(null);
            if (threadId == null) return;
            Map<String, Object> pushStateData = queryLatestState(threadId);
            if (pushStateData == null) return;
            OverAllState state = stateSerializer.stateOf(pushStateData);

            boolean interrupted = (lastOutput instanceof InterruptionMetadata);
            // 收尾节点名（中断场景由 interrupted 单独判定，无需节点名）
            String lastNodeName = interrupted ? null : (lastOutput != null ? lastOutput.node() : null);
            // BLOCK 判据（FR-BE-007）：优先消费跨线程 BLOCK 标记（rcagent_blocked_end 在异步工作流线程写入，
            // 而 lastOutput.node()/checkpoint risk_level 实测不可靠），再回退收尾节点名/State risk_level=BLOCK。
            // 提前计算供「元数据 remark / SSE 终态值」复用，保证 /state 与 SSE 末事件语义一致。
            boolean blockedByGuard = consumeBlocked(threadId) || isBlockedSignal(state, lastNodeName);
            String errorDetail = errorStatusDetails.get(threadId);
            RcaWorkflowStatus finalStatus = resolveFinalStatus(state, lastNodeName, interrupted, errorDetail,
                    blockedByGuard);
            String status = finalStatus.name();
            log.info("RCAgent pushFinalState: threadId={}, interrupted={}, status={}, blocked={}",
                    threadId, interrupted, status, blockedByGuard);

            // 更新元数据表最终状态 + 快照字段
            RcaWorkflowMetadata meta = new RcaWorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setStatus(status);
            if ("FAILED".equals(status)) {
                if (errorDetail != null) {
                    // 异常链路：沿用既有可读错误原因（去 'FAILED — ' 前缀，≤500 截断）
                    meta.setRemark(truncate(errorDetail.replaceFirst("^FAILED — ", ""), 500));
                } else if (blockedByGuard) {
                    // BLOCK 链路：写可读失败原因，供 /state 的 message 直接兜底返回（FR-BE-008/009，DC-01）
                    meta.setRemark(truncate(BLOCKED_FAILED_REASON, 500));
                }
            }
            // 快照字段（前端历史/详情回看）
            meta.setIntentType(RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.INTENT_TYPE, null));
            meta.setRiskLevel(RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RISK_LEVEL, null));
            String reviewDecision = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.REVIEW_DECISION, "");
            meta.setReviewDecision(reviewDecision.isBlank() ? null : reviewDecision);
            String reviewFeedback = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.REVIEW_FEEDBACK, "");
            meta.setReviewFeedback(reviewFeedback.isBlank() ? null : reviewFeedback);
            String summary = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RESULT_SUMMARY, "");
            meta.setResultSummary(summary.isBlank() ? null : summary);
            updateMetadataStatus(meta);

            // 内容字段用 RUNNING 推送，仅 workflow_status 携带终态；
            // 终态推送不含审核敏感字段：SQL 草案/审核内容仅经审核通道可见（修复审查问题 1）；
            // module-003 追加 4 个跨源内部键（join_hint/join_plan/source_query_plans/source_query_results）
            // 至排除清单（单源会话不含这些键 ⇒ 排除判断恒 false，行为不变；cross_source_note 保留推送）。
            for (Map.Entry<String, Object> entry : state.data().entrySet()) {
                String key = entry.getKey();
                if (RcaWorkflowKeys.SQL_GENERATE.equals(key) || RcaWorkflowKeys.REVIEW_CONTENT.equals(key)
                        || RcaWorkflowKeys.JOIN_HINT.equals(key) || RcaWorkflowKeys.JOIN_PLAN.equals(key)
                        || RcaWorkflowKeys.SOURCE_QUERY_PLANS.equals(key)
                        || RcaWorkflowKeys.SOURCE_QUERY_RESULTS.equals(key)) {
                    continue;
                }
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                eventBus.publish(threadId, key, value, "RUNNING");
            }
            String removedErrorDetail = errorStatusDetails.remove(threadId);
            String terminalValue;
            if (removedErrorDetail != null && status.equals("FAILED")) {
                terminalValue = removedErrorDetail;
            } else if (status.equals("FAILED") && blockedByGuard) {
                // BLOCK 终止态：与 rcagent_blocked_end 已推的 workflow_status 值保持一致（DC-02）
                terminalValue = BLOCKED_STATUS_VALUE;
            } else {
                terminalValue = status;
            }
            eventBus.publish(threadId, RcaWorkflowKeys.WORKFLOW_STATUS, terminalValue, status);
        } catch (Exception e) {
            log.error("RCAgent pushFinalState 内部失败，工作流终态可能丢失", e);
            try {
                String threadId = config.threadId().orElse(null);
                if (threadId != null && eventBus != null) {
                    eventBus.publish(threadId, RcaWorkflowKeys.WORKFLOW_STATUS,
                            "FAILED — 系统异常", "FAILED");
                }
            } catch (Exception ignored) {}
        }
    }

    /**
     * 确定性读取最新 checkpoint state（应用层绕过框架 MysqlSaver 同秒排序不稳定问题）。
     */
    private Map<String, Object> queryLatestState(String threadId) {
        try {
            String checkpointId = metadataMapper.selectLatestCheckpointId(threadId);
            if (checkpointId == null || checkpointId.isBlank()) return null;
            String stateData = metadataMapper.selectCheckpointStateData(checkpointId);
            if (stateData == null || stateData.isBlank()) return null;
            String base64 = extractBinaryPayload(stateData);
            if (base64 == null) return null;
            byte[] bytes = java.util.Base64.getDecoder().decode(base64);
            return stateSerializer.dataFromBytes(bytes);
        } catch (Exception e) {
            log.warn("RCAgent 确定性读取 checkpoint 失败: threadId={}, err={}", threadId, e.getMessage());
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

    /**
     * 工作流终态判定（包级可见静态方法，便于单测；FR-BE-007/FR-BE-011）。
     *
     * <p>优先级链：
     * ① 命中 interruptAfter 中断 → {@code WAITING_REVIEW}；
     * ② 存在以 {@code FAILED} 开头的异常详情 → {@code FAILED}（异常链路保持既有优先级）；
     * ③ State 显式终态（{@code COMPLETED/TERMINATED/FAILED/WAITING_REVIEW}）→ 对应终态；
     * ④ BLOCK 判据（收尾节点为 {@code rcagent_blocked_end}，或 State {@code risk_level=BLOCK}）→ {@code FAILED}；
     * ⑤ 其余 RUNNING/SENT_BACK（无显式终态）→ 有异常详情则 {@code FAILED}，否则 {@code COMPLETED} 兜底（既有行为，零回归）。</p>
     *
     * <p>注意：判定为 {@code FAILED} 且 {@code errorDetail} 为空、命中 BLOCK 判据时，可读失败原因取
     * {@link #BLOCKED_FAILED_REASON}。</p>
     *
     * @param state        最新 checkpoint State（允许 null）
     * @param lastNodeName 收尾节点名（中断场景可为 null）
     * @param interrupted  是否由 interruptAfter 中断
     * @param errorDetail  异常链路错误详情（可 null）
     */
    static RcaWorkflowStatus resolveFinalStatus(OverAllState state, String lastNodeName,
            boolean interrupted, String errorDetail) {
        // 兼容既有 4 参签名：无跨线程 BLOCK 标记入参，退化为纯 checkpoint/节点名判据（零回归）
        return resolveFinalStatus(state, lastNodeName, interrupted, errorDetail, false);
    }

    /**
     * 终态判定重载：在既有 4 参判据基础上增加 {@code blockedByGuard} 跨线程 BLOCK 标记入参（④ 判据）。
     * <p>用于解决 rcagent_blocked_end 节点已执行，但 {@code lastOutput.node()} 与 checkpoint
     * {@code risk_level} 均不可靠时，BLOCK 被误判为 COMPLETED 的问题（FR-BE-007）。</p>
     *
     * <p>优先级链：① 中断 → WAITING_REVIEW；② errorDetail 以 FAILED 开头 → FAILED；③ State 显式终态；
     * ④ {@code blockedByGuard || isBlockedSignal(state,lastNodeName)} → FAILED；⑤ 兜底 COMPLETED/FAILED。</p>
     */
    static RcaWorkflowStatus resolveFinalStatus(OverAllState state, String lastNodeName,
            boolean interrupted, String errorDetail, boolean blockedByGuard) {
        if (interrupted) {
            return RcaWorkflowStatus.WAITING_REVIEW;
        }
        if (errorDetail != null && errorDetail.startsWith("FAILED")) {
            return RcaWorkflowStatus.FAILED;
        }
        RcaWorkflowStatus resolved = resolveStatus(state, false);
        if (resolved == RcaWorkflowStatus.FAILED || resolved == RcaWorkflowStatus.TERMINATED
                || resolved == RcaWorkflowStatus.COMPLETED || resolved == RcaWorkflowStatus.WAITING_REVIEW) {
            return resolved;
        }
        // ④ BLOCK 判据：跨线程标记（可靠）或 checkpoint 信号，避免 BLOCK 被误判为 COMPLETED（FR-BE-007）
        if (blockedByGuard || isBlockedSignal(state, lastNodeName)) {
            return RcaWorkflowStatus.FAILED;
        }
        // ⑤ RUNNING/SENT_BACK 且无显式终态：沿用既有兜底（有异常详情 → FAILED，否则 COMPLETED）
        return errorDetail != null ? RcaWorkflowStatus.FAILED : RcaWorkflowStatus.COMPLETED;
    }

    /** BLOCK 判据：收尾节点为 rcagent_blocked_end，或 State {@code risk_level=BLOCK} */
    private static boolean isBlockedSignal(OverAllState state, String lastNodeName) {
        if (RCA_BLOCKED_END_NODE.equals(lastNodeName)) {
            return true;
        }
        return state != null && RISK_LEVEL_BLOCK.equals(
                RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.RISK_LEVEL, ""));
    }

    private static RcaWorkflowStatus resolveStatus(OverAllState state, boolean interrupted) {
        if (interrupted) return RcaWorkflowStatus.WAITING_REVIEW;
        if (state == null) return RcaWorkflowStatus.RUNNING;
        String workflowStatus = RcaWorkflowKeys.extractStateText(state, RcaWorkflowKeys.WORKFLOW_STATUS, "");
        if ("WAITING_REVIEW".equals(workflowStatus)) return RcaWorkflowStatus.WAITING_REVIEW;
        if ("SENT_BACK".equals(workflowStatus)) return RcaWorkflowStatus.SENT_BACK;
        if ("TERMINATED".equals(workflowStatus)) return RcaWorkflowStatus.TERMINATED;
        if ("FAILED".equals(workflowStatus)) return RcaWorkflowStatus.FAILED;
        if ("COMPLETED".equals(workflowStatus)) return RcaWorkflowStatus.COMPLETED;
        return RcaWorkflowStatus.RUNNING;
    }

    private static boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "TERMINATED".equals(status) || "FAILED".equals(status);
    }

    /**
     * 对外输出的 State 净化（占位鉴权阶段安全策略，修复审查问题 1）。
     * <ul>
     *   <li>先执行通用序列化 sanitize（复杂对象转字符串/Message 解包）</li>
     *   <li>SQL 草案(sql_generate)一律替换为脱敏占位——草案全文仅审核通道/审核审计可见</li>
     *   <li>审核内容(review_content)仅 WAITING_REVIEW 审核态返回原文（供审核面板），其余状态剥离为占位</li>
     * </ul>
     * 真实 RBAC 落地后按 40101 越权码区分审核人与普通用户（TODO: module-010 鉴权接入点，可参照
     * application.yml attribution.review.reviewer-user-ids fail-closed 模式）。
     */
    private Map<String, Object> buildPublicState(Map<String, Object> stateData, boolean allowReviewContent) {
        if (stateData == null || stateData.isEmpty()) {
            return stateData;
        }
        Map<String, Object> publicState = new HashMap<>(sanitizeStateForSerialization(stateData));
        Object sqlDraft = publicState.get(RcaWorkflowKeys.SQL_GENERATE);
        if (sqlDraft != null) {
            publicState.put(RcaWorkflowKeys.SQL_GENERATE,
                    "[SQL 草案：仅审核通道可见（长度 " + String.valueOf(sqlDraft).length() + "）]");
        }
        if (publicState.containsKey(RcaWorkflowKeys.REVIEW_CONTENT) && !allowReviewContent) {
            Object reviewContent = publicState.get(RcaWorkflowKeys.REVIEW_CONTENT);
            publicState.put(RcaWorkflowKeys.REVIEW_CONTENT,
                    "[审核内容：仅审核员可见（长度 "
                            + String.valueOf(reviewContent == null ? 0 : reviewContent).length() + "）]");
        }
        // module-003：4 个跨源内部键一律替换为脱敏占位（cross_source_note 自身已脱敏，原文返回）；
        // 键缺失时 containsKey 为 false ⇒ 单源会话行为逐字符不变。
        maskInternalKey(publicState, RcaWorkflowKeys.JOIN_HINT);
        maskInternalKey(publicState, RcaWorkflowKeys.JOIN_PLAN);
        maskInternalKey(publicState, RcaWorkflowKeys.SOURCE_QUERY_PLANS);
        maskInternalKey(publicState, RcaWorkflowKeys.SOURCE_QUERY_RESULTS);
        return publicState;
    }

    /** 跨源内部键脱敏占位（仅审核通道可见；长度仅用于可观测，不泄露内容） */
    private static void maskInternalKey(Map<String, Object> publicState, String key) {
        if (!publicState.containsKey(key)) {
            return;
        }
        Object value = publicState.get(key);
        publicState.put(key, "[跨源内部数据：仅审核通道可见（长度 "
                + String.valueOf(value == null ? 0 : value).length() + "）]");
    }

    private Map<String, Object> sanitizeStateForSerialization(Map<String, Object> stateData) {
        if (stateData == null || stateData.isEmpty()) return stateData;
        return stateData.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> sanitizeValue(entry.getValue())));
    }

    private Object sanitizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof org.springframework.ai.chat.messages.Message msg) {
            return msg.getText();
        }
        if (value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Enum) {
            return value;
        }
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapValue = (Map<String, Object>) value;
            return sanitizeStateForSerialization(mapValue);
        }
        String className = value.getClass().getName();
        if (className.startsWith("com.alibaba.cloud.ai.graph")) {
            try {
                java.lang.reflect.Method method = value.getClass().getMethod("resultValue");
                Object opt = method.invoke(value);
                if (opt instanceof Optional<?> o && o.isPresent()) {
                    return sanitizeValue(o.get());
                }
            } catch (Exception ignored) {}
            String str = value.toString();
            if (str.startsWith(className + "@")) {
                return "[Complex Object: " + className.substring(className.lastIndexOf('.') + 1) + "]";
            }
            return str;
        }
        return value.toString();
    }

    private void updateMetadataStatus(RcaWorkflowMetadata patch) {
        try {
            RcaWorkflowMetadata meta = metadataMapper.selectById(patch.getThreadId());
            if (meta == null) {
                return;
            }
            // 合并 patch 非空字段到完整实体（null 不动），避免 LambdaUpdateWrapper.set(null) 清空已存字段
            if (patch.getDsId() != null) meta.setDsId(patch.getDsId());
            if (patch.getQuestion() != null) meta.setQuestion(patch.getQuestion());
            if (patch.getIntentType() != null) meta.setIntentType(patch.getIntentType());
            if (patch.getStatus() != null) meta.setStatus(patch.getStatus());
            if (patch.getRiskLevel() != null) meta.setRiskLevel(patch.getRiskLevel());
            if (patch.getReviewDecision() != null) meta.setReviewDecision(patch.getReviewDecision());
            if (patch.getReviewFeedback() != null) meta.setReviewFeedback(patch.getReviewFeedback());
            if (patch.getResultSummary() != null) meta.setResultSummary(patch.getResultSummary());
            if (patch.getRemark() != null) meta.setRemark(patch.getRemark());
            meta.setUpdateTime(new Date());
            metadataMapper.updateById(meta);
        } catch (Exception e) {
            log.error("RCAgent 更新元数据失败: threadId={}", patch.getThreadId(), e);
        }
    }

    private void handleWorkflowException(String threadId, RunnableConfig config, Exception e) {
        try {
            String friendlyMsg = toFriendlyErrorMessage(e);
            String errorStatus = "FAILED — " + truncate(friendlyMsg, 80);
            errorStatusDetails.put(threadId, errorStatus);
            eventBus.publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, friendlyMsg, "RUNNING");
            eventBus.publish(threadId, RcaWorkflowKeys.WORKFLOW_STATUS, errorStatus, "FAILED");
            log.info("RCAgent 异常已推送到SSE: threadId={}, msg={}", threadId, truncate(friendlyMsg, 50));
            RcaWorkflowMetadata meta = new RcaWorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setStatus("FAILED");
            meta.setRemark(truncate(friendlyMsg, 300));
            updateMetadataStatus(meta);
        } catch (Exception ex) {
            log.error("RCAgent handleWorkflowException 内部失败: threadId={}", threadId, ex);
        }
    }

    private void pushErrorToSse(String threadId, Throwable e) {
        try {
            String friendlyMsg = toFriendlyErrorMessage(e);
            String errorStatus = "FAILED — " + truncate(friendlyMsg, 80);
            errorStatusDetails.put(threadId, errorStatus);
            eventBus.publish(threadId, RcaWorkflowKeys.WORKFLOW_MESSAGE, friendlyMsg, "RUNNING");
            RcaWorkflowMetadata meta = new RcaWorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setStatus("FAILED");
            meta.setRemark(truncate(friendlyMsg, 300));
            updateMetadataStatus(meta);
        } catch (Exception ex) {
            log.error("RCAgent pushErrorToSse 内部失败: threadId={}", threadId, ex);
        }
    }

    private String toFriendlyErrorMessage(Throwable e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";
        String className = e.getClass().getSimpleName();
        String fullClassChain = getClassChain(e);
        // 403 / 429 / timeout / connect / tool callback —— 与 Rd 语义一致
        if (msg.contains("403") || msg.contains("Forbidden")) {
            return "LLM API 返回 403 Forbidden — 可能是 API Key 额度已用完或权限不足。"
                    + "（原始错误: " + truncate(msg, 200) + "）";
        }
        if (msg.contains("429") || msg.contains("Too Many Requests")) {
            return "LLM API 返回 429 — 请求过于频繁被限流，请稍后重试。"
                    + "（原始错误: " + truncate(msg, 200) + "）";
        }
        if (msg.contains("timeout") || msg.contains("Timeout")
                || className.contains("Timeout") || fullClassChain.contains("Timeout")) {
            return "LLM API 请求超时 — 请检查网络连接或稍后重试。"
                    + "（原始错误: " + truncate(msg, 200) + "）";
        }
        if (msg.contains("Connection refused") || fullClassChain.contains("ConnectException")
                || fullClassChain.contains("ClosedChannelException")) {
            return "无法连接到 LLM API 服务 — 请检查 LLM 服务是否正常运行、网络是否可达。"
                    + "（原始错误: " + truncate(msg, 200) + "）";
        }
        return "RCAgent 执行异常: " + className + " — " + truncate(msg, 300);
    }

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

    private Long effectiveCreatorId() {
        Long id = BaseContext.getCurrentId();
        return id != null ? id : 0L;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
