package com.agenthub.ai.rcagent.kb.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.rcagent.kb.dto.KbReviewApproveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbReviewRejectDTO;
import com.agenthub.ai.rcagent.kb.dto.KbReviewTaskCreateDTO;
import com.agenthub.ai.rcagent.kb.dto.KbReviewTaskPageDTO;
import com.agenthub.ai.rcagent.kb.service.KbReviewService;
import com.agenthub.ai.rcagent.kb.vo.KbReviewApproveVO;
import com.agenthub.ai.rcagent.kb.vo.KbReviewRejectVO;
import com.agenthub.ai.rcagent.kb.vo.KbReviewTaskCreateVO;
import com.agenthub.ai.rcagent.kb.vo.KbReviewTaskDetailVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审核任务中心 API（接口11-15）。
 */
@Tag(name = "KbReviewController", description = "知识库A-审核任务中心（create/page/detail/approve/reject）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/kb/review")
public class KbReviewController {

    private final KbReviewService reviewService;

    public KbReviewController(KbReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @Operation(summary = "创建审核任务")
    @PostMapping("/task/create")
    public BaseResponse<KbReviewTaskCreateVO> createTask(@RequestBody KbReviewTaskCreateDTO dto) {
        return ResultUtils.success(reviewService.create(dto));
    }

    @Operation(summary = "审核任务分页")
    @PostMapping("/task/page")
    public BaseResponse<PageResult> pageTask(@RequestBody KbReviewTaskPageDTO dto) {
        return ResultUtils.success(reviewService.page(dto));
    }

    @Operation(summary = "审核任务详情")
    @GetMapping("/task/{id}")
    public BaseResponse<KbReviewTaskDetailVO> taskDetail(@PathVariable Long id) {
        return ResultUtils.success(reviewService.detail(id));
    }

    @Operation(summary = "审核批准")
    @PostMapping("/task/{id}/approve")
    public BaseResponse<KbReviewApproveVO> approve(@PathVariable Long id,
                                                   @RequestBody KbReviewApproveDTO dto) {
        return ResultUtils.success(reviewService.approve(id, dto));
    }

    @Operation(summary = "审核拒绝")
    @PostMapping("/task/{id}/reject")
    public BaseResponse<KbReviewRejectVO> reject(@PathVariable Long id,
                                                 @RequestBody KbReviewRejectDTO dto) {
        return ResultUtils.success(reviewService.reject(id, dto));
    }
}
