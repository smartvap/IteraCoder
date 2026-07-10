package com.agenthub.ai.workflow.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.workflow.config.RdWorkflowGraphConfig;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.constant.RdWorkflowReviewDecision;
import com.agenthub.ai.workflow.constant.RdWorkflowStatus;
import com.agenthub.ai.workflow.vo.RdWorkflowResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 研发工作流服务（阶段一：需求拆解 → 并行推理 → 人工审核）。
 */
@Slf4j
@Service
public class RdWorkflowService {

    private final CompiledGraph rdWorkflowCompiledGraph;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public RdWorkflowService(CompiledGraph rdWorkflowCompiledGraph) {
        this.rdWorkflowCompiledGraph = rdWorkflowCompiledGraph;
    }

    /**
     * 启动研发工作流（异步）。
     */
    public RdWorkflowResultVO start(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "研发需求不能为空");
        }
        String threadId = UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> input = new HashMap<>();
        input.put(RdWorkflowKeys.REQUIREMENT, requirement.trim());
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        CompletableFuture.runAsync(() -> {
            try {
                AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
                rdWorkflowCompiledGraph.stream(input, config)
                        .doOnNext(lastOutput::set)
                        .blockLast();
                log.info("工作流执行完成: threadId={}", threadId);
            } catch (Exception e) {
                log.error("工作流执行异常: threadId={}", threadId, e);
            }
        }, executor);

        log.info("工作流已启动（异步）: threadId={}", threadId);
        return RdWorkflowResultVO.builder()
                .threadId(threadId)
                .status(RdWorkflowStatus.RUNNING)
                .message("工作流已启动，正在执行中...")
                .build();
    }

    /**
     * 人工审核后恢复。
     * <p>
     * APPROVED → 标记 COMPLETED，同步返回<br>
     * TERMINATED → 标记 TERMINATED，同步返回<br>
     * SENT_BACK → 异步回到需求拆解重跑
     */
    public RdWorkflowResultVO resume(String threadId, RdWorkflowReviewDecision decision, String comment) {
        if (threadId == null || threadId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "threadId 不能为空");
        }
        if (decision == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "审核决策不能为空");
        }

        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();

        try {
            // 前置检查：终态工作流不允许恢复
            var snapshot = rdWorkflowCompiledGraph.getState(config);
            if (snapshot != null && snapshot.state() != null) {
                String existingDecision = snapshot.state()
                        .value(RdWorkflowKeys.REVIEW_DECISION, "").toString();
                if ("TERMINATED".equals(existingDecision)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "工作流已终止，无法再次操作");
                }
                String status = snapshot.state()
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

            // APPROVED → COMPLETED，同步返回
            if (RdWorkflowReviewDecision.APPROVED.equals(decision)) {
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_STATUS, "COMPLETED");
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_MESSAGE, "审核通过，工作流完成");
                RunnableConfig updatedConfig = rdWorkflowCompiledGraph.updateState(
                        config, stateUpdate, RdWorkflowGraphConfig.MANUAL_REVIEW_NODE);
                OverAllState state = rdWorkflowCompiledGraph.getState(updatedConfig).state();
                return RdWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RdWorkflowStatus.COMPLETED)
                        .interrupted(false)
                        .state(sanitizeStateForSerialization(state.data()))
                        .message("审核通过，工作流完成")
                        .build();
            }

            // TERMINATED → 同步返回
            if (RdWorkflowReviewDecision.TERMINATED.equals(decision)) {
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_STATUS, "TERMINATED");
                String msg = (comment != null && !comment.isBlank()) ? comment : "工作流已终止";
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_MESSAGE, msg);
                RunnableConfig updatedConfig = rdWorkflowCompiledGraph.updateState(
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

            // SENT_BACK：回到需求拆解重跑，清除后续节点残留值
            stateUpdate.put(RdWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
            stateUpdate.put(RdWorkflowKeys.DECOMPOSITION_RESULT, null);
            stateUpdate.put(RdWorkflowKeys.PARALLEL_REASONING_RESULT, null);
            // 动态清除所有 reasoning_*_result 字段
            for (String key : snapshot.state().data().keySet()) {
                if (key.startsWith("reasoning_") && key.endsWith("_result")) {
                    stateUpdate.put(key, null);
                }
            }

            RunnableConfig updatedConfig = rdWorkflowCompiledGraph.updateState(
                    config, stateUpdate, RdWorkflowGraphConfig.MANUAL_REVIEW_NODE);

            // SENT_BACK：异步恢复执行
            final RunnableConfig resumeConfig = updatedConfig;
            CompletableFuture.runAsync(() -> {
                try {
                    AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
                    rdWorkflowCompiledGraph.stream(null, resumeConfig)
                            .doOnNext(lastOutput::set)
                            .doOnError(e -> log.error("恢复流程异常", e))
                            .blockLast();
                    log.info("工作流恢复执行完成: threadId={}", threadId);
                } catch (Exception e) {
                    log.error("工作流恢复执行异常: threadId={}", threadId, e);
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
     * 查询工作流当前状态。
     */
    public RdWorkflowResultVO getState(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "threadId 不能为空");
        }
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        try {
            var snapshot = rdWorkflowCompiledGraph.getState(config);
            if (snapshot == null || snapshot.state() == null) {
                return RdWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RdWorkflowStatus.RUNNING)
                        .message("工作流正在初始化中...")
                        .build();
            }
            OverAllState state = snapshot.state();
            RdWorkflowStatus status = resolveStatus(state);
            String message = state.value(RdWorkflowKeys.WORKFLOW_MESSAGE, "").toString();
            if (status == RdWorkflowStatus.RUNNING && message.isBlank()) {
                message = "工作流执行中...";
            }
            return RdWorkflowResultVO.builder()
                    .threadId(threadId)
                    .status(status)
                    .interrupted(status == RdWorkflowStatus.WAITING_REVIEW)
                    .interruptedNode(status == RdWorkflowStatus.WAITING_REVIEW
                            ? RdWorkflowGraphConfig.MANUAL_REVIEW_NODE : null)
                    .state(sanitizeStateForSerialization(state.data()))
                    .message(message)
                    .build();
        } catch (IllegalStateException e) {
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

    private RdWorkflowStatus resolveStatus(OverAllState state) {
        String reviewDecision = state.value(RdWorkflowKeys.REVIEW_DECISION, "").toString();
        if ("TERMINATED".equals(reviewDecision)) {
            return RdWorkflowStatus.TERMINATED;
        }
        String workflowStatus = state.value(RdWorkflowKeys.WORKFLOW_STATUS, "").toString();
        if ("COMPLETED".equals(workflowStatus)) {
            return RdWorkflowStatus.COMPLETED;
        }
        if ("WAITING_REVIEW".equals(workflowStatus)) {
            return RdWorkflowStatus.WAITING_REVIEW;
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
                        entry -> {
                            Object value = entry.getValue();
                            String className = value.getClass().getName();
                            if (className.startsWith("com.alibaba.cloud.ai.graph")) {
                                return "[Complex Object: " + className + ", not serializable]";
                            }
                            if (value instanceof String || value instanceof Number ||
                                    value instanceof Boolean || value instanceof Enum) {
                                return value;
                            }
                            if (value instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> mapValue = (Map<String, Object>) value;
                                return sanitizeStateForSerialization(mapValue);
                            }
                            return value.toString();
                        }
                ));
    }
}
