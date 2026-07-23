package com.agenthub.ai.workflow.service;

import com.agenthub.ai.workflow.node.MultiRoundAgentNode;
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
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.mapper.WorkflowMetadataMapper;
import com.agenthub.ai.workflow.entity.WorkflowMetadata;
import com.agenthub.ai.workflow.vo.RdWorkflowResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
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
 * 智能体研发工作流服务（阶段一：需求拆解 → 并行推理 → 人工审核）。
 */
@Slf4j
@Service
public class RdWorkflowService {

    private final CompiledGraph rdWorkflowCompiledGraph;
    private final WorkflowEventBus eventBus;
    private final WorkflowMetadataMapper metadataMapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public RdWorkflowService(CompiledGraph rdWorkflowCompiledGraph, WorkflowEventBus eventBus,
            WorkflowMetadataMapper metadataMapper) {
        this.rdWorkflowCompiledGraph = rdWorkflowCompiledGraph;
        this.eventBus = eventBus;
        this.metadataMapper = metadataMapper;
    }

    private RunnableConfig buildConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    /**
     * 启动智能体研发工作流（异步）。
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

        WorkflowMetadata meta = new WorkflowMetadata();
        meta.setThreadId(threadId);
        meta.setRequirement(requirement.trim());
        meta.setReviewFeedback("");
        meta.setStatus("RUNNING");
        meta.setCreateTime(new Date());
        meta.setUpdateTime(new Date());
        metadataMapper.insert(meta);

        CompletableFuture.runAsync(() -> {
            try {
                AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
                rdWorkflowCompiledGraph.stream(input, config)
                        .doOnNext(lastOutput::set)
                        .blockLast();
                pushFinalState(config, lastOutput.get());
                log.info("智能体工作流执行完成: threadId={}", threadId);
            } catch (Exception e) {
                log.error("智能体工作流执行异常: threadId={}", threadId, e);
                updateMetadataStatus(threadId, "FAILED");
            }
        }, executor);

        log.info("智能体工作流已启动（异步）: threadId={}", threadId);
        return RdWorkflowResultVO.builder()
                .threadId(threadId)
                .status(RdWorkflowStatus.RUNNING)
                .message("智能体工作流已启动，正在执行中...")
                .build();
    }

    /**
     * 人工审核后恢复。
     * <p>
     * APPROVED → COMPLETED，同步返回<br>
     * TERMINATED → TERMINATED，同步返回<br>
     * SENT_BACK → 异步回到需求拆解重跑
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
            var snapshot = rdWorkflowCompiledGraph.getState(config);
            if (snapshot != null && snapshot.state() != null) {
                String existingDecision = snapshot.state()
                        .value(RdWorkflowKeys.REVIEW_DECISION, "").toString();
                if ("TERMINATED".equals(existingDecision)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "智能体工作流已终止，无法再次操作");
                }
                String status = snapshot.state()
                        .value(RdWorkflowKeys.WORKFLOW_STATUS, "").toString();
                if ("COMPLETED".equals(status)) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "智能体工作流已完成，无法再次操作");
                }
            }

            Map<String, Object> stateUpdate = new HashMap<>();
            stateUpdate.put(RdWorkflowKeys.REVIEW_DECISION, decision.name());
            stateUpdate.put(RdWorkflowKeys.REVIEW_FEEDBACK,
                    (comment != null && !comment.isBlank()) ? comment : "");
            stateUpdate.put(MultiRoundAgentNode.THREAD_ID_KEY, threadId);

            // APPROVED → COMPLETED，同步返回
            if (RdWorkflowReviewDecision.APPROVED.equals(decision)) {
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_STATUS, "COMPLETED");
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_MESSAGE, "审核通过，进入代码生成中");

                WorkflowMetadata meta = new WorkflowMetadata();
                meta.setThreadId(threadId);
                meta.setReviewFeedback(comment != null ? comment : "");
                meta.setStatus("COMPLETED");
                meta.setUpdateTime(new Date());
                metadataMapper.updateById(meta);

                eventBus.clearEvents(threadId);

                RunnableConfig updatedConfig = rdWorkflowCompiledGraph.updateState(
                        config, stateUpdate, RdWorkflowGraphConfig.MANUAL_REVIEW_NODE);
                OverAllState state = rdWorkflowCompiledGraph.getState(updatedConfig).state();
                return RdWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RdWorkflowStatus.COMPLETED)
                        .interrupted(false)
                        .state(sanitizeStateForSerialization(state.data()))
                        .message("审核通过，进入代码生成中")
                        .build();
            }

            // TERMINATED → 同步返回
            if (RdWorkflowReviewDecision.TERMINATED.equals(decision)) {
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_STATUS, "TERMINATED");
                String msg = (comment != null && !comment.isBlank()) ? comment : "智能体工作流已终止";
                stateUpdate.put(RdWorkflowKeys.WORKFLOW_MESSAGE, msg);

                WorkflowMetadata meta = new WorkflowMetadata();
                meta.setThreadId(threadId);
                meta.setReviewFeedback(comment != null ? comment : "");
                meta.setStatus("TERMINATED");
                meta.setUpdateTime(new Date());
                metadataMapper.updateById(meta);

                eventBus.clearEvents(threadId);

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

            // SENT_BACK：回到需求拆解重跑
            stateUpdate.put(RdWorkflowKeys.WORKFLOW_STATUS, "RUNNING");
            stateUpdate.put(RdWorkflowKeys.WORKFLOW_MESSAGE, "");
            stateUpdate.put(RdWorkflowKeys.DECOMPOSITION_RESULT, null);
            stateUpdate.put(RdWorkflowKeys.PARALLEL_REASONING_RESULT, null);
            stateUpdate.put(RdWorkflowKeys.REVIEW_CONTENT, null);
            for (String key : snapshot.state().data().keySet()) {
                if (key.startsWith("reasoning_") && key.endsWith("_result")) {
                    stateUpdate.put(key, null);
                }
            }

            WorkflowMetadata meta = new WorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setReviewFeedback(comment != null ? comment : "");
            meta.setStatus("RUNNING");
            meta.setUpdateTime(new Date());
            metadataMapper.updateById(meta);

            eventBus.clearEvents(threadId);

            RunnableConfig updatedConfig = rdWorkflowCompiledGraph.updateState(
                    config, stateUpdate, RdWorkflowGraphConfig.MANUAL_REVIEW_NODE);

            final RunnableConfig resumeConfig = updatedConfig;
            CompletableFuture.runAsync(() -> {
                try {
                    AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
                    rdWorkflowCompiledGraph.stream(null, resumeConfig)
                            .doOnNext(lastOutput::set)
                            .doOnError(e -> log.error("恢复流程异常", e))
                            .blockLast();
                    pushFinalState(resumeConfig, lastOutput.get());
                    log.info("智能体工作流恢复执行完成: threadId={}", threadId);
                } catch (Exception e) {
                    log.error("智能体工作流恢复执行异常: threadId={}", threadId, e);
                    updateMetadataStatus(threadId, "FAILED");
                }
            }, executor);

            return RdWorkflowResultVO.builder()
                    .threadId(threadId)
                    .status(RdWorkflowStatus.RUNNING)
                    .message("审核决策已提交，智能体工作流继续执行中...")
                    .build();
        } catch (Exception e) {
            log.error("智能体工作流恢复失败, threadId={}, decision={}", threadId, decision, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "智能体工作流恢复失败: " + e.getMessage());
        }
    }

    /**
     * 查询智能体工作流当前状态。
     */
    public RdWorkflowResultVO getState(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "threadId 不能为空");
        }
        RunnableConfig config = buildConfig(threadId);
        try {
            var snapshot = rdWorkflowCompiledGraph.getState(config);
            if (snapshot == null || snapshot.state() == null) {
                return RdWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RdWorkflowStatus.RUNNING)
                        .message("智能体工作流正在初始化中...")
                        .build();
            }
            OverAllState state = snapshot.state();

            RdWorkflowStatus status = resolveStatus(state, false);

            WorkflowMetadata metadata = metadataMapper.selectById(threadId);
            if (metadata != null && isTerminal(metadata.getStatus())) {
                status = RdWorkflowStatus.valueOf(metadata.getStatus());
            }
            String message = state.value(RdWorkflowKeys.WORKFLOW_MESSAGE, "").toString();
            if (status == RdWorkflowStatus.RUNNING && message.isBlank()) {
                message = "智能体工作流执行中...";
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
            if (e.getMessage() != null && e.getMessage().contains("Missing Checkpoint")) {
                log.debug("智能体工作流 checkpoint 尚未就绪: threadId={}", threadId);
                return RdWorkflowResultVO.builder()
                        .threadId(threadId)
                        .status(RdWorkflowStatus.RUNNING)
                        .message("智能体工作流正在启动中...")
                        .build();
            }
            throw e;
        } catch (Exception e) {
            log.error("查询智能体工作流状态失败, threadId={}", threadId, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "查询智能体工作流状态失败: " + e.getMessage());
        }
    }

    public java.util.List<com.agenthub.ai.workflow.vo.WorkflowRecordVO> listRecords(
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
        wrapper.orderByDesc(WorkflowMetadata::getCreateTime);
        wrapper.last("LIMIT 500");

        return metadataMapper.selectList(wrapper).stream()
                .map(r -> com.agenthub.ai.workflow.vo.WorkflowRecordVO.builder()
                        .threadId(r.getThreadId())
                        .requirement(r.getRequirement())
                        .reviewFeedback(r.getReviewFeedback())
                        .status(r.getStatus())
                        .createTime(r.getCreateTime())
                        .updateTime(r.getUpdateTime())
                        .build())
                .toList();
    }

    // ===== 私有方法 =====

    private RdWorkflowStatus resolveStatus(OverAllState state, boolean interrupted) {
        if (interrupted) return RdWorkflowStatus.WAITING_REVIEW;

        String decompResult = state.value(RdWorkflowKeys.DECOMPOSITION_RESULT, "").toString();
        if (RdWorkflowKeys.isNotDevReq(decompResult)) return RdWorkflowStatus.COMPLETED;

        String reviewDecision = state.value(RdWorkflowKeys.REVIEW_DECISION, "").toString();
        if ("TERMINATED".equals(reviewDecision)) return RdWorkflowStatus.TERMINATED;

        String workflowStatus = state.value(RdWorkflowKeys.WORKFLOW_STATUS, "").toString();
        if ("COMPLETED".equals(workflowStatus)) return RdWorkflowStatus.COMPLETED;
        if ("WAITING_REVIEW".equals(workflowStatus)) return RdWorkflowStatus.WAITING_REVIEW;

        return RdWorkflowStatus.RUNNING;
    }

    private Map<String, Object> sanitizeStateForSerialization(Map<String, Object> stateData) {
        if (stateData == null || stateData.isEmpty()) return stateData;
        return stateData.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> sanitizeValue(entry.getValue())
                ));
    }

    private Object sanitizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof org.springframework.ai.chat.messages.Message msg) return msg.getText();
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum) return value;
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
                if (opt instanceof Optional<?> o && o.isPresent()) return sanitizeValue(o.get());
            } catch (Exception ignored) {}
            String str = value.toString();
            if (str.startsWith(className + "@")) return "[Complex Object: " + className.substring(className.lastIndexOf('.') + 1) + "]";
            return str;
        }
        return value.toString();
    }

    private void pushFinalState(RunnableConfig config, NodeOutput lastOutput) {
        try {
            String threadId = config.threadId().orElse(null);
            if (threadId == null) return;

            var snapshot = rdWorkflowCompiledGraph.getState(config);
            if (snapshot == null || snapshot.state() == null) return;
            OverAllState state = snapshot.state();

            boolean interrupted = (lastOutput instanceof InterruptionMetadata);

            String status;
            if (interrupted) {
                status = "WAITING_REVIEW";
            } else {
                RdWorkflowStatus resolved = resolveStatus(state, false);
                status = (resolved == RdWorkflowStatus.RUNNING)
                        ? RdWorkflowStatus.COMPLETED.name()
                        : resolved.name();
            }

            log.info("pushFinalState: threadId={}, interrupted={}, status={}", threadId, interrupted, status);

            WorkflowMetadata meta = new WorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setStatus(status);
            meta.setUpdateTime(new Date());
            metadataMapper.updateById(meta);

            for (Map.Entry<String, Object> entry : state.data().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                eventBus.publish(threadId, key, value, "RUNNING");
            }
            eventBus.publish(threadId, RdWorkflowKeys.WORKFLOW_STATUS, status, status);
        } catch (Exception e) {
            log.warn("推送最终状态事件失败", e);
        }
    }

    private void updateMetadataStatus(String threadId, String status) {
        try {
            WorkflowMetadata meta = new WorkflowMetadata();
            meta.setThreadId(threadId);
            meta.setStatus(status);
            meta.setUpdateTime(new Date());
            metadataMapper.updateById(meta);
        } catch (Exception e) {
            log.warn("更新元数据状态失败: threadId={}, status={}", threadId, status, e);
        }
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "TERMINATED".equals(status) || "FAILED".equals(status);
    }
}
