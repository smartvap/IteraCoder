package com.agenthub.ai.rcagent.attribution.controller;

import com.agenthub.ai.rcagent.attribution.dto.AttributionAnalyzeRequest;
import com.agenthub.ai.rcagent.attribution.dto.AttributionFollowUpRequest;
import com.agenthub.ai.rcagent.attribution.dto.AttributionReviewDecision;
import com.agenthub.ai.rcagent.attribution.dto.AttributionReviewPageQuery;
import com.agenthub.ai.rcagent.attribution.dto.AttributionTaskPageQuery;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionTask;
import com.agenthub.ai.rcagent.attribution.service.AttributionService;
import com.agenthub.ai.rcagent.attribution.vo.AttributionCancelResultVO;
import com.agenthub.ai.rcagent.attribution.vo.AttributionConfigVO;
import com.agenthub.ai.rcagent.attribution.vo.AttributionResultVO;
import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.common.ResultUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 归因分析工作流 API（module-007 rca-attribution-workflow，8 个 HTTP 端点）。
 *
 * <p>安全约束（AGENTS.md 5.x）：Controller 不打印 question 全量、不入参 SQL 原文；
 * include_sql=true 的 SQL 展示属审核场景受控返回，已在 Service 侧做 40300 审核人名单门闸
 * （最小实现，真实 RBAC 落地后替换为角色/权限点校验）。</p>
 */
@Tag(name = "AttributionController", description = "归因分析工作流（analyze/follow-up/task/review/config）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/attribution")
public class AttributionController {

    private final AttributionService attributionService;

    public AttributionController(AttributionService attributionService) {
        this.attributionService = attributionService;
    }

    /** 接口1 发起归因 */
    @Operation(summary = "发起归因（指标异常归因或用户追问归因）")
    @PostMapping("/analyze")
    public BaseResponse<AttributionResultVO> analyze(@RequestBody AttributionAnalyzeRequest request) {
        if (request == null) {
            return ResultUtils.error(com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode.PARAM_ILLEGAL,
                    "请求参数不能为空");
        }
        return ResultUtils.success(attributionService.analyze(request));
    }

    /** 接口2 追问下钻 */
    @Operation(summary = "追问下钻（携带 taskId/上下文继续下钻）")
    @PostMapping("/follow-up")
    public BaseResponse<AttributionResultVO> followUp(@RequestBody AttributionFollowUpRequest request) {
        if (request == null) {
            return ResultUtils.error(com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode.PARAM_ILLEGAL,
                    "请求参数不能为空");
        }
        return ResultUtils.success(attributionService.followUp(request));
    }

    /** 接口3 归因任务详情 */
    @Operation(summary = "归因任务详情（含下钻树/因子/查询/结论/审核留痕）")
    @GetMapping("/task/{taskId}")
    public BaseResponse<AttributionResultVO> getTask(@PathVariable String taskId,
                                                     @RequestParam(value = "include_sql", required = false,
                                                             defaultValue = "false") Boolean includeSql) {
        boolean withSql = Boolean.TRUE.equals(includeSql);
        return ResultUtils.success(attributionService.getDetail(taskId, withSql));
    }

    /** 接口4 归因任务分页 */
    @Operation(summary = "归因历史分页")
    @PostMapping("/task/page")
    public BaseResponse<PageResult> pageTasks(@RequestBody AttributionTaskPageQuery query) {
        if (query == null) {
            return ResultUtils.error(com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode.PARAM_ILLEGAL,
                    "请求参数不能为空");
        }
        return ResultUtils.success(attributionService.pageTasks(query));
    }

    /** 接口5 归因审核分页 */
    @Operation(summary = "归因审核分页（QUERY/CONCLUSION 待审与已审）")
    @PostMapping("/review/page")
    public BaseResponse<PageResult> pageReviews(@RequestBody AttributionReviewPageQuery query) {
        if (query == null) {
            return ResultUtils.error(com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode.PARAM_ILLEGAL,
                    "请求参数不能为空");
        }
        return ResultUtils.success(attributionService.pageReviews(query));
    }

    /** 接口6 审核决策 */
    @Operation(summary = "审核决策（放行/打回归因 SQL 或归因结论）")
    @PostMapping("/review/decision")
    public BaseResponse<AttributionResultVO> decideReview(@RequestBody AttributionReviewDecision decision) {
        if (decision == null) {
            return ResultUtils.error(com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode.PARAM_ILLEGAL,
                    "请求参数不能为空");
        }
        return ResultUtils.success(attributionService.decideReview(decision));
    }

    /** 接口7 取消/回退归因任务 */
    @Operation(summary = "取消任务或回退到指定下钻层")
    @PostMapping("/task/{taskId}/cancel")
    public BaseResponse<AttributionCancelResultVO> cancel(@PathVariable String taskId,
                                                          @RequestBody(required = false) CancelRequest body) {
        Integer resetToLayer = body == null ? null : body.getResetToLayer();
        RcagentAttributionTask updated = attributionService.cancelOrReset(taskId, resetToLayer);
        return ResultUtils.success(AttributionCancelResultVO.of(updated), "ok");
    }

    /** 接口8 归因规则配置 */
    @Operation(summary = "读取归因规则/阈值/上限")
    @GetMapping("/config")
    public BaseResponse<AttributionConfigVO> getConfig() {
        return ResultUtils.success(attributionService.getConfig());
    }

    /** 取消请求体（reset_to_layer 缺省=取消任务） */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class CancelRequest {

        private Integer resetToLayer;

        private String reason;

        public Integer getResetToLayer() {
            return resetToLayer;
        }

        public void setResetToLayer(Integer resetToLayer) {
            this.resetToLayer = resetToLayer;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
