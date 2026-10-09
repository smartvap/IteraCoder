package com.agenthub.ai.rcagent.kb.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.rcagent.kb.dto.KbConfidenceConfirmDTO;
import com.agenthub.ai.rcagent.kb.dto.KbConfidenceSignalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbStructureChangeDTO;
import com.agenthub.ai.rcagent.kb.service.KbConfidenceService;
import com.agenthub.ai.rcagent.kb.vo.KbConfidenceConfirmVO;
import com.agenthub.ai.rcagent.kb.vo.KbConfidenceNegativeVO;
import com.agenthub.ai.rcagent.kb.vo.KbConfidencePositiveVO;
import com.agenthub.ai.rcagent.kb.vo.KbStructureChangeVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 置信度在线更新 API（接口16-19）。
 */
@Tag(name = "KbConfidenceController", description = "知识库A-置信度引擎（positive/negative/confirm/structure-change）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/kb/confidence")
public class KbConfidenceController {

    private final KbConfidenceService confidenceService;

    public KbConfidenceController(KbConfidenceService confidenceService) {
        this.confidenceService = confidenceService;
    }

    @Operation(summary = "置信度正面信号")
    @PostMapping("/positive")
    public BaseResponse<KbConfidencePositiveVO> positive(@RequestBody KbConfidenceSignalDTO dto) {
        return ResultUtils.success(confidenceService.positive(dto));
    }

    @Operation(summary = "置信度负面信号")
    @PostMapping("/negative")
    public BaseResponse<KbConfidenceNegativeVO> negative(@RequestBody KbConfidenceSignalDTO dto) {
        return ResultUtils.success(confidenceService.negative(dto));
    }

    @Operation(summary = "置信度人工确认")
    @PostMapping("/confirm")
    public BaseResponse<KbConfidenceConfirmVO> confirm(@RequestBody KbConfidenceConfirmDTO dto) {
        return ResultUtils.success(confidenceService.confirm(dto));
    }

    @Operation(summary = "结构变更影响")
    @PostMapping("/structure-change")
    public BaseResponse<KbStructureChangeVO> structureChange(@RequestBody KbStructureChangeDTO dto) {
        return ResultUtils.success(confidenceService.structureChange(dto));
    }
}
