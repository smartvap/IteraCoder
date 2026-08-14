package com.agenthub.ai.workflow.controller;

import com.agenthub.ai.base.annotation.Loggable;
import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.workflow.dto.RdWorkflowResumeRequest;
import com.agenthub.ai.workflow.dto.RdWorkflowStartRequest;
import com.agenthub.ai.workflow.dto.WorkflowRecordQueryDTO;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.service.RdWorkflowService;
import com.agenthub.ai.workflow.vo.RdWorkflowResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * 研发智能体工作流 API
 */
@Tag(name = "RdWorkflowController", description = "研发智能体工作流（StateGraph + ReactAgent）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/workflow")
public class RdWorkflowController {

    private final RdWorkflowService rdWorkflowService;
    private final WorkflowEventBus eventBus;

    @Value("${spring.mvc.async.request-timeout:900000}")
    private long sseTimeoutMs;

    public RdWorkflowController(RdWorkflowService rdWorkflowService, WorkflowEventBus eventBus) {
        this.rdWorkflowService = rdWorkflowService;
        this.eventBus = eventBus;
    }

    @Operation(summary = "启动研发工作流", description = "提交原始研发需求，执行需求拆解→并行推理→人工审核暂停")
    @PostMapping("/start")
    @Loggable
    public BaseResponse<RdWorkflowResultVO> start(@RequestBody RdWorkflowStartRequest request) {
        log.info("启动研发工作流: {}", request.getRequirement());
        return ResultUtils.success(rdWorkflowService.start(request.getRequirement()));
    }

    @Operation(summary = "人工审核恢复", description = "提交审核决策 APPROVED/SENT_BACK/TERMINATED 恢复流程")
    @PostMapping("/resume")
    @Loggable
    public BaseResponse<RdWorkflowResultVO> resume(@RequestBody RdWorkflowResumeRequest request) {
        log.info("恢复工作流: threadId={}, decision={}", request.getThreadId(), request.getReviewDecision());
        return ResultUtils.success(rdWorkflowService.resume(
                request.getThreadId(),
                request.getReviewDecision(),
                request.getComment()));
    }

    @Operation(summary = "查询工作流状态")
    @GetMapping("/state/{threadId}")
    public BaseResponse<RdWorkflowResultVO> getState(@PathVariable String threadId) {
        return ResultUtils.success(rdWorkflowService.getState(threadId));
    }

    @Operation(summary = "查询历史工作流列表",
            description = "支持按时间段、状态、需求模糊搜索，分页查询。参数均可选。")
    @GetMapping("/list")
    public BaseResponse<com.agenthub.ai.base.common.PageResult> listRecords(WorkflowRecordQueryDTO query) {
        return ResultUtils.success(rdWorkflowService.listRecords(query));
    }

    @Operation(summary = "手动终止工作流", description = "将 RUNNING 状态的流程标记为 TERMINATED")
    @PostMapping("/terminate/{threadId}")
    @Loggable
    public BaseResponse<String> terminate(@PathVariable String threadId) {
        rdWorkflowService.terminateWorkflow(threadId);
        return ResultUtils.success("已终止");
    }

    @Operation(summary = "从 checkpoint 恢复执行", description = "服务重启后从中断点继续执行工作流")
    @PostMapping("/recover/{threadId}")
    @Loggable
    public BaseResponse<RdWorkflowResultVO> recover(@PathVariable String threadId) {
        RdWorkflowResultVO result = rdWorkflowService.recover(threadId);
        return ResultUtils.success(result);
    }

    @GetMapping(value = "/events/{threadId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String threadId) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs); // 从 yml 配置读取超时
        // 标记 emitter 是否已完成（超时/错误/正常关闭后不再 send，避免 IllegalStateException）
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);

        // emitter 超时/错误回调：标记完成，避免工作流线程继续推送时崩溃
        emitter.onTimeout(() -> {
            completed.set(true);
            log.warn("SSE 连接超时: threadId={}", threadId);
        });
        emitter.onError(t -> {
            completed.set(true);
            log.warn("SSE 连接错误: threadId={}, error={}", threadId, t.getMessage());
        });

        new Thread(() -> {
            String lastStatus = null;
            // 记录连接建立时的 epoch，只消费当前代的事件，过滤旧代残留
            final int connectionEpoch = eventBus.getEpoch(threadId);
            try {
                while (!completed.get()) {
                    WorkflowEventBus.Event event = eventBus.subscribe(threadId, 5000);
                    if (event != null) {
                        // epoch 过滤：丢弃旧代事件（如 resume 前的 pushFinalState 残留）
                        if (event.epoch() != connectionEpoch) {
                            continue; // 跳过旧代事件，不发送给前端
                        }
                        // emitter 已完成则停止推送
                        if (completed.get()) break;

                        Map<String, Object> sseData = new java.util.HashMap<>();
                        sseData.put("key", event.key());
                        sseData.put("value", event.value());
                        sseData.put("status", event.status());
                        if (event.isDelta()) {
                            sseData.put("_delta", true);
                        }
                        try {
                            emitter.send(SseEmitter.event()
                                    .data(sseData, MediaType.APPLICATION_JSON));
                        } catch (IllegalStateException ise) {
                            // emitter 已完成（超时/客户端断开），停止推送
                            completed.set(true);
                            log.debug("SSE emitter 已完成，停止推送: threadId={}", threadId);
                            break;
                        }
                        // 仅 workflow_status 终态事件关闭连接，避免内容同步事件误触发断连
                        if ("workflow_status".equals(event.key())) {
                            lastStatus = event.status();
                            String s = event.status();
                            if ("COMPLETED".equals(s) || "TERMINATED".equals(s)
                                    || "FAILED".equals(s) || "WAITING_REVIEW".equals(s)) {
                                break;
                            }
                        }
                    }
                }
                // 等发送缓冲 flush 到客户端，避免 content 事件丢失
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                if (!completed.get()) {
                    emitter.complete();
                }
                // 只有真正的终态才清理队列；WAITING_REVIEW 不清理（驳回后复用同一 threadId）
                if (lastStatus != null && !lastStatus.equals("WAITING_REVIEW")) {
                    eventBus.removeChannel(threadId);
                }
            } catch (IOException e) {
                if (!completed.get()) {
                    emitter.completeWithError(e);
                }
            }
        }, "sse-" + threadId).start();
        return emitter;
    }

    /** 下载流程项目代码（支持多选，zip 打包） */
    @GetMapping("/download-code")
    @ResponseBody
    public void downloadCode(@RequestParam("threadIds") java.util.Set<String> threadIds,
            jakarta.servlet.http.HttpServletResponse response) throws IOException {
        rdWorkflowService.downloadCode(threadIds, response);
    }
}
