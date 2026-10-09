package com.agenthub.ai.rcagent.retrieval.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalErrorCode;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalLogPageQuery;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalRequest;
import com.agenthub.ai.rcagent.retrieval.dto.RetrievalStatsQuery;
import com.agenthub.ai.rcagent.retrieval.service.RetrievalService;
import com.agenthub.ai.rcagent.retrieval.vo.RetrievalSearchVO;
import com.agenthub.ai.rcagent.retrieval.vo.RetrievalStatsVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 双重知识库融合检索 API（dual-retrieval 3 个接口）。
 */
@Tag(name = "RetrievalController", description = "双重知识库融合检索（search/log/page/log/stats）")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/retrieval")
public class RetrievalController {

    private final RetrievalService retrievalService;

    public RetrievalController(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    /** 接口1 融合检索主入口 */
    @Operation(summary = "融合检索主入口")
    @PostMapping("/search")
    public BaseResponse<RetrievalSearchVO> search(@RequestBody RetrievalRequest request) {
        if (request == null) {
            throw new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "请求参数不能为空");
        }
        // 手动 if 校验（项目未启用 @Valid），业务侧再次兜底校验
        return ResultUtils.success(RetrievalSearchVO.from(retrievalService.search(request)));
    }

    /** 接口2 融合检索质量日志分页 */
    @Operation(summary = "融合检索质量日志分页")
    @PostMapping("/log/page")
    public BaseResponse<PageResult> pageLogs(@RequestBody RetrievalLogPageQuery query) {
        if (query == null) {
            throw new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "请求参数不能为空");
        }
        if (query.getPage() != null && query.getPage() < 1) {
            throw new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "page 需 >= 1");
        }
        if (query.getPageSize() != null && (query.getPageSize() < 1 || query.getPageSize() > 100)) {
            throw new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "page_size 需在 1~100 之间");
        }
        return ResultUtils.success(retrievalService.pageLogs(query));
    }

    /** 接口3 融合检索质量指标聚合 */
    @Operation(summary = "融合检索质量指标聚合")
    @PostMapping("/log/stats")
    public BaseResponse<RetrievalStatsVO> stats(@RequestBody RetrievalStatsQuery query) {
        if (query == null) {
            throw new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "请求参数不能为空");
        }
        if (query.getStartTime() == null || query.getStartTime().isBlank()
                || query.getEndTime() == null || query.getEndTime().isBlank()) {
            throw new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "start_time/end_time 必填");
        }
        if (query.getGranularity() != null && !query.getGranularity().isBlank()
                && !"day".equalsIgnoreCase(query.getGranularity())
                && !"week".equalsIgnoreCase(query.getGranularity())) {
            throw new BusinessException(RetrievalErrorCode.PARAM_ILLEGAL, "granularity 非法");
        }
        return ResultUtils.success(retrievalService.stats(query));
    }
}
