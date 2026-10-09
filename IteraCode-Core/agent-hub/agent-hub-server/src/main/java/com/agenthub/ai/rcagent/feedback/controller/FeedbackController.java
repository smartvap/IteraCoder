package com.agenthub.ai.rcagent.feedback.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.rcagent.feedback.dto.ClassifyRequest;
import com.agenthub.ai.rcagent.feedback.dto.FeedbackPageRequest;
import com.agenthub.ai.rcagent.feedback.dto.FeedbackStatsRequest;
import com.agenthub.ai.rcagent.feedback.dto.FeedbackSubmitRequest;
import com.agenthub.ai.rcagent.feedback.dto.WritebackRequest;
import com.agenthub.ai.rcagent.feedback.service.FeedbackAnalysisService;
import com.agenthub.ai.rcagent.feedback.service.FeedbackClassifyService;
import com.agenthub.ai.rcagent.feedback.service.FeedbackQueryService;
import com.agenthub.ai.rcagent.feedback.service.FeedbackSubmitService;
import com.agenthub.ai.rcagent.feedback.service.FeedbackWritebackService;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackAnalyzeVO;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackClassifyVO;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackFlowVO;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackStatsVO;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackSubmitVO;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackWritebackVO;
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
 * 反馈闭环与知识沉淀 API（module-008，7 接口）。
 *
 * <p>统一 /api/v1/rca/feedback/**；接收反馈 → 分析 → 建议 → 审核 → 回写 → 置信度推进
 * 全链路驱动。审核批准/拒绝动作由 module-004 review API 完成，本模块调度器轮询
 * approved 自动触发回写（接口7），审核 UI 也可批准后显式触发加速闭环。</p>
 */
@Tag(name = "FeedbackController", description = "反馈闭环与知识沉淀（提交/详情/列表/统计/分析/人工分类/回写）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/rca/feedback")
public class FeedbackController {

    private final FeedbackSubmitService submitService;
    private final FeedbackQueryService queryService;
    private final FeedbackAnalysisService analysisService;
    private final FeedbackClassifyService classifyService;
    private final FeedbackWritebackService writebackService;

    public FeedbackController(FeedbackSubmitService submitService,
                              FeedbackQueryService queryService,
                              FeedbackAnalysisService analysisService,
                              FeedbackClassifyService classifyService,
                              FeedbackWritebackService writebackService) {
        this.submitService = submitService;
        this.queryService = queryService;
        this.analysisService = analysisService;
        this.classifyService = classifyService;
        this.writebackService = writebackService;
    }

    @Operation(summary = "提交反馈（评价/SQL 纠错/归因/口径补充）")
    @PostMapping
    public BaseResponse<FeedbackSubmitVO> submit(@RequestBody FeedbackSubmitRequest request) {
        return ResultUtils.success(submitService.submit(request));
    }

    @Operation(summary = "反馈闭环详情")
    @GetMapping("/{flowId}")
    public BaseResponse<FeedbackFlowVO> detail(@PathVariable Long flowId) {
        return ResultUtils.success(queryService.detail(flowId));
    }

    @Operation(summary = "反馈闭环分页列表")
    @PostMapping("/page")
    public BaseResponse<PageResult> page(@RequestBody FeedbackPageRequest request) {
        return ResultUtils.success(queryService.page(request));
    }

    @Operation(summary = "反馈运营统计")
    @PostMapping("/stats")
    public BaseResponse<FeedbackStatsVO> stats(@RequestBody FeedbackStatsRequest request) {
        return ResultUtils.success(queryService.stats(request));
    }

    @Operation(summary = "触发/重跑分析（AST diff + 修改类型判断 + 建议生成 + 创建审核任务）")
    @PostMapping("/{flowId}/analyze")
    public BaseResponse<FeedbackAnalyzeVO> analyze(@PathVariable Long flowId) {
        var flow = analysisService.analyze(flowId);
        FeedbackAnalyzeVO vo = new FeedbackAnalyzeVO();
        vo.setFlowId(flow.getId());
        vo.setAnalyzeStatus(flow.getAnalyzeStatus());
        vo.setStatus(flow.getStatus());
        vo.setChangeType(flow.getChangeType());
        vo.setChangeConfidence(flow.getChangeConfidence());
        vo.setReviewTaskIds(com.agenthub.ai.rcagent.feedback.util.FeedbackJsonUtil.parseLongList(flow.getReviewTaskIds()));
        return ResultUtils.success(vo);
    }

    @Operation(summary = "人工判定修改类型（MANUAL_REQUIRED 兜底后继续建议建审）")
    @PostMapping("/{flowId}/classify")
    public BaseResponse<FeedbackClassifyVO> classify(@PathVariable Long flowId,
                                                     @RequestBody ClassifyRequest request) {
        return ResultUtils.success(classifyService.classify(flowId, request));
    }

    @Operation(summary = "触发回写（审核通过后 A/B 库回写 + 置信度推进，幂等可重试）")
    @PostMapping("/{flowId}/writeback")
    public BaseResponse<FeedbackWritebackVO> writeback(@PathVariable Long flowId,
                                                       @RequestBody(required = false) WritebackRequest request) {
        boolean force = request != null && request.force();
        return ResultUtils.success(writebackService.writeback(flowId, force));
    }
}
