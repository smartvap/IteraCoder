package com.agenthub.ai.rcagent.kb.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.rcagent.kb.dto.KbFeedbackPageDTO;
import com.agenthub.ai.rcagent.kb.dto.KbFeedbackSaveDTO;
import com.agenthub.ai.rcagent.kb.service.KbFeedbackService;
import com.agenthub.ai.rcagent.kb.vo.KbFeedbackSaveVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈记录 API（接口25-26）。
 */
@Tag(name = "KbFeedbackController", description = "知识库A-反馈记录（save/page）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/kb/feedback")
public class KbFeedbackController {

    private final KbFeedbackService feedbackService;

    public KbFeedbackController(KbFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @Operation(summary = "反馈落库")
    @PostMapping("/save")
    public BaseResponse<KbFeedbackSaveVO> save(@RequestBody KbFeedbackSaveDTO dto) {
        return ResultUtils.success(feedbackService.save(dto));
    }

    @Operation(summary = "反馈分页查询")
    @PostMapping("/page")
    public BaseResponse<PageResult> page(@RequestBody KbFeedbackPageDTO dto) {
        return ResultUtils.success(feedbackService.page(dto));
    }
}
