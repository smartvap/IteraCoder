package com.agenthub.ai.rcagent.engine.controller;

import com.agenthub.ai.base.annotation.Loggable;
import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.rcagent.engine.dto.RcaFeedbackRequest;
import com.agenthub.ai.rcagent.engine.dto.RcaSessionQueryDTO;
import com.agenthub.ai.rcagent.engine.dto.RcaSourceResolveRequest;
import com.agenthub.ai.rcagent.engine.dto.RcaWorkflowResumeRequest;
import com.agenthub.ai.rcagent.engine.dto.RcaWorkflowStartRequest;
import com.agenthub.ai.rcagent.engine.service.RcaWorkflowService;
import com.agenthub.ai.rcagent.engine.support.RcaSourceGroupService;
import com.agenthub.ai.rcagent.engine.vo.RcaSourceGroupVO;
import com.agenthub.ai.rcagent.engine.vo.RcaSourceResolveVO;
import com.agenthub.ai.rcagent.engine.vo.RcaWorkflowResultVO;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * RCAgent 工作流 API（8 个接口）。
 *
 * <p>接口安全（修复审查问题 1）：审核内容（SQL 草案/归因结论）默认脱敏——/state 中 SQL 草案一律占位、
 * review_content 仅 WAITING_REVIEW 审核态返回；SQL 生成阶段 SSE 不推送草案全文（仅审核通道事件可见）。
 * 当前 JWT 为占位鉴权（无 RBAC，AGENTS.md 4.2），无法区分审核人；真实 RBAC 落地后需在
 * getState/streamEvents 入口补 40101 越权校验（TODO: module-010 鉴权接入点）。
 * {@code @Loggable} 入参不含 Token/密钥。SSE 终态事件关闭连接。</p>
 */
@Tag(name = "RcaWorkflowController", description = "RCAgent 查数归因工作流（StateGraph）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/rcagent")
public class RcaWorkflowController {

    private final RcaWorkflowService rcaWorkflowService;
    private final WorkflowEventBus eventBus;
    private final RcaSourceGroupService rcaSourceGroupService;

    @Value("${spring.mvc.async.request-timeout:900000}")
    private long sseTimeoutMs;

    public RcaWorkflowController(RcaWorkflowService rcaWorkflowService, WorkflowEventBus eventBus,
            RcaSourceGroupService rcaSourceGroupService) {
        this.rcaWorkflowService = rcaWorkflowService;
        this.eventBus = eventBus;
        this.rcaSourceGroupService = rcaSourceGroupService;
    }

    @Operation(summary = "启动 RCAgent 会话", description = "提交问题+查询范围(dsId/systemCode/dsIds)，可选指定跨源 join(joinType/leftDsId/rightDsId/leftJoinKey/rightJoinKey)，异步启动会话，立即返回 threadId")
    @PostMapping("/start")
    @Loggable("RCAgent 启动会话")
    public BaseResponse<RcaWorkflowResultVO> start(@RequestBody RcaWorkflowStartRequest request) {
        // 日志仅记录长度与是否传了跨源 join 字段（禁止打印 dsId/关联键/Token 等）
        log.info("RCAgent 启动会话: questionLength={}, joinHintProvided={}",
                request.getQuestion() == null ? 0 : request.getQuestion().length(),
                hasJoinField(request));
        return ResultUtils.success(rcaWorkflowService.start(request));
    }

    /** 是否传入任一跨源 join 字段（仅用于日志标记，不打印取值） */
    private static boolean hasJoinField(RcaWorkflowStartRequest request) {
        return request.getJoinType() != null || request.getLeftDsId() != null
                || request.getRightDsId() != null || request.getLeftJoinKey() != null
                || request.getRightJoinKey() != null;
    }

    @Operation(summary = "系统分组视图", description = "返回 system_code + 候选源清单（仅非敏感字段），供前端系统下拉与预览")
    @GetMapping("/sources/systems")
    public BaseResponse<List<RcaSourceGroupVO>> listSourceSystems(
            @RequestParam(value = "includeUngrouped", required = false) Boolean includeUngrouped) {
        return ResultUtils.success(rcaSourceGroupService.listSystems(Boolean.TRUE.equals(includeUngrouped)));
    }

    @Operation(summary = "候选源解析校验", description = "服务端权威解析候选源集合并校验（存在性/同组/上限/ACTIVE），供前端提交前预检；与 start 复用同一解析服务")
    @PostMapping("/sources/resolve")
    public BaseResponse<RcaSourceResolveVO> resolveSources(@RequestBody RcaSourceResolveRequest request) {
        return ResultUtils.success(rcaSourceGroupService.resolveCandidates(
                request.getDsId(), request.getSystemCode(), request.getDsIds()));
    }

    @Operation(summary = "人工审核恢复", description = "提交审核决策 APPROVED/SENT_BACK/TERMINATED 恢复会话")
    @PostMapping("/resume")
    @Loggable("RCAgent 审核恢复")
    public BaseResponse<RcaWorkflowResultVO> resume(@RequestBody RcaWorkflowResumeRequest request) {
        log.info("RCAgent 恢复会话: threadId={}, decision={}", request.getThreadId(),
                request.getReviewDecision());
        return ResultUtils.success(rcaWorkflowService.resume(
                request.getThreadId(),
                request.getReviewDecision(),
                request.getComment()));
    }

    @Operation(summary = "查询会话状态", description = "查询会话状态/进度/结果摘要/审核信息；审核内容在 WAITING_REVIEW 态经审核通道返回，其余状态脱敏")
    @GetMapping("/state/{threadId}")
    public BaseResponse<RcaWorkflowResultVO> getState(@PathVariable String threadId) {
        // TODO(module-010 真实 RBAC)：此处应校验当前登录用户对会话/审核内容的权限，越权返回 40101
        return ResultUtils.success(rcaWorkflowService.getState(threadId));
    }

    @Operation(summary = "从 checkpoint 恢复", description = "失败/中断后从 checkpoint 续跑（WAITING_REVIEW 状态禁止 recover）")
    @PostMapping("/recover/{threadId}")
    @Loggable("RCAgent 从 checkpoint 恢复")
    public BaseResponse<RcaWorkflowResultVO> recover(@PathVariable String threadId) {
        return ResultUtils.success(rcaWorkflowService.recover(threadId));
    }

    @Operation(summary = "手动终止会话", description = "将 RUNNING 状态会话标记为 TERMINATED")
    @PostMapping("/terminate/{threadId}")
    @Loggable("RCAgent 手动终止")
    public BaseResponse<String> terminate(@PathVariable String threadId) {
        rcaWorkflowService.terminateWorkflow(threadId);
        return ResultUtils.success("已终止");
    }

    @Operation(summary = "会话事件流（SSE）", description = "text/event-stream 实时推送节点进度/审核/终态；SQL 草案全文不在 SSE 推送（审核敏感字段经审核事件通道）")
    @GetMapping(value = "/events/{threadId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String threadId) {
        // TODO(module-010 真实 RBAC)：SSE 订阅同样需校验会话可见性；审核内容（review_content 事件）仅允许审核角色订阅，越权返回 40101
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        emitter.onTimeout(() -> completed.set(true));
        emitter.onError(t -> completed.set(true));

        new Thread(() -> {
            String lastStatus = null;
            final int connectionEpoch = eventBus.getEpoch(threadId);
            try {
                while (!completed.get()) {
                    WorkflowEventBus.Event event = eventBus.subscribe(threadId, 5000);
                    if (event != null) {
                        if (event.epoch() != connectionEpoch) {
                            continue;
                        }
                        if (completed.get()) break;
                        Map<String, Object> sseData = new java.util.HashMap<>();
                        sseData.put("key", event.key());
                        sseData.put("value", event.value());
                        sseData.put("status", event.status());
                        if (event.isDelta()) {
                            sseData.put("_delta", true);
                        }
                        try {
                            emitter.send(SseEmitter.event().data(sseData, MediaType.APPLICATION_JSON));
                        } catch (IllegalStateException ise) {
                            completed.set(true);
                            break;
                        }
                        if ("workflow_status".equals(event.key())) {
                            lastStatus = event.status();
                            if ("COMPLETED".equals(event.status()) || "TERMINATED".equals(event.status())
                                    || "FAILED".equals(event.status()) || "WAITING_REVIEW".equals(event.status())) {
                                break;
                            }
                        }
                    }
                }
                if (!completed.get()) {
                    emitter.complete();
                }
                if (lastStatus != null && !lastStatus.equals("WAITING_REVIEW")) {
                    eventBus.removeChannel(threadId);
                }
            } catch (IOException e) {
                if (!completed.get()) {
                    emitter.completeWithError(e);
                }
            }
        }, "rca-sse-" + threadId).start();
        return emitter;
    }

    @Operation(summary = "会话历史分页", description = "按状态/数据源/用户/时间/问题模糊查询")
    @GetMapping("/sessions")
    public BaseResponse<PageResult> sessions(RcaSessionQueryDTO query) {
        return ResultUtils.success(rcaWorkflowService.listRecords(query));
    }

    @Operation(summary = "结果反馈入口", description = "结果页反馈写入（转发 feedback-loop 占位契约）")
    @PostMapping("/feedback")
    public BaseResponse<String> feedback(@RequestBody RcaFeedbackRequest request) {
        log.info("RCAgent 反馈: threadId={}, feedbackType={}", request.getThreadId(), request.getFeedbackType());
        return ResultUtils.success(rcaWorkflowService.recordFeedback(
                request.getThreadId(), request.getFeedbackType(), request.getComment()));
    }
}
