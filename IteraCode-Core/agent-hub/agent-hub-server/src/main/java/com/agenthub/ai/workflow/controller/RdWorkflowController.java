package com.agenthub.ai.workflow.controller;

import com.agenthub.ai.base.annotation.Loggable;
import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.workflow.dto.RdWorkflowResumeRequest;
import com.agenthub.ai.workflow.dto.RdWorkflowStartRequest;
import com.agenthub.ai.workflow.service.RdWorkflowService;
import com.agenthub.ai.workflow.vo.RdWorkflowResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 研发智能体工作流 API
 */
@Tag(name = "RdWorkflowController", description = "研发智能体工作流（StateGraph + ReactAgent）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/workflow")
@RequiredArgsConstructor
public class RdWorkflowController {

    private final RdWorkflowService rdWorkflowService;

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
}
