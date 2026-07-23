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
import com.agenthub.ai.workflow.vo.WorkflowRecordVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
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
            description = "支持按时间段、状态、需求模糊搜索。参数均可选。")
    @GetMapping("/list")
    public BaseResponse<java.util.List<WorkflowRecordVO>> listRecords(WorkflowRecordQueryDTO query) {
        return ResultUtils.success(rdWorkflowService.listRecords(query));
    }

    @GetMapping(value = "/events/{threadId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String threadId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 分钟超时
        new Thread(() -> {
            String lastStatus = null;
            // 记录连接建立时的 epoch，只消费当前代的事件，过滤旧代残留
            final int connectionEpoch = eventBus.getEpoch(threadId);
            try {
                while (true) {
                    WorkflowEventBus.Event event = eventBus.subscribe(threadId, 5000);
                    if (event != null) {
                        // epoch 过滤：丢弃旧代事件（如 resume 前的 pushFinalState 残留）
                        if (event.epoch() != connectionEpoch) {
                            continue; // 跳过旧代事件，不发送给前端
                        }
                        Map<String, Object> sseData = new java.util.HashMap<>();
                        sseData.put("key", event.key());
                        sseData.put("value", event.value());
                        sseData.put("status", event.status());
                        if (event.isDelta()) {
                            sseData.put("_delta", true);
                        }
                        emitter.send(SseEmitter.event()
                                .data(sseData, MediaType.APPLICATION_JSON));
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
                emitter.complete();
                // 只有真正的终态才清理队列；WAITING_REVIEW 不清理（驳回后复用同一 threadId）
                if (lastStatus != null && !lastStatus.equals("WAITING_REVIEW")) {
                    eventBus.removeChannel(threadId);
                }
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }, "sse-" + threadId).start();
        return emitter;
    }
}
