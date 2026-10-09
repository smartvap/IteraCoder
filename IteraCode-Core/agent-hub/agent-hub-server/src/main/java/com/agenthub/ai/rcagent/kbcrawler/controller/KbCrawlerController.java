package com.agenthub.ai.rcagent.kbcrawler.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.rcagent.kbcrawler.dto.CrawlPauseResumeDTO;
import com.agenthub.ai.rcagent.kbcrawler.dto.CrawlTriggerDTO;
import com.agenthub.ai.rcagent.kbcrawler.dto.ReleaseTriggerDTO;
import com.agenthub.ai.rcagent.kbcrawler.dto.RuntimeReportDTO;
import com.agenthub.ai.rcagent.kbcrawler.service.KbChangeDetectService;
import com.agenthub.ai.rcagent.kbcrawler.service.KbCrawlerTaskService;
import com.agenthub.ai.rcagent.kbcrawler.service.KbRuntimeCollectService;
import com.agenthub.ai.rcagent.kbcrawler.vo.ChangeDetectResultVO;
import com.agenthub.ai.rcagent.kbcrawler.vo.CrawlTriggerResultVO;
import com.agenthub.ai.rcagent.kbcrawler.vo.KbCrawlTaskStatusVO;
import com.agenthub.ai.rcagent.kbcrawler.vo.PauseResultVO;
import com.agenthub.ai.rcagent.kbcrawler.vo.ResumeResultVO;
import com.agenthub.ai.rcagent.kbcrawler.vo.RuntimeReportResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * kb-schema-crawler 采集运营 API（接口1~7）：手动触发/状态查询/列表/暂停/恢复/
 * 运行时上报/发版触发变更检测。
 */
@Tag(name = "KbCrawlerController", description = "知识自动采集管线-运营接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/kb-crawler")
public class KbCrawlerController {

    private final KbCrawlerTaskService taskService;
    private final KbRuntimeCollectService runtimeCollectService;
    private final KbChangeDetectService changeDetectService;

    public KbCrawlerController(KbCrawlerTaskService taskService,
                               KbRuntimeCollectService runtimeCollectService,
                               KbChangeDetectService changeDetectService) {
        this.taskService = taskService;
        this.runtimeCollectService = runtimeCollectService;
        this.changeDetectService = changeDetectService;
    }

    /** 接口1 手动触发采集任务 */
    @Operation(summary = "手动触发采集任务")
    @PostMapping("/task/trigger")
    public BaseResponse<CrawlTriggerResultVO> trigger(@RequestBody CrawlTriggerDTO dto) {
        return ResultUtils.success(taskService.trigger(dto.getDsId(), dto.getTaskType(),
                dto.getMode(), dto.getTriggerType()));
    }

    /** 接口2 采集任务状态查询 */
    @Operation(summary = "采集任务状态查询")
    @GetMapping("/task/status")
    public BaseResponse<List<KbCrawlTaskStatusVO>> status(@RequestParam("ds_id") String dsId,
                                                          @RequestParam(value = "task_type", required = false) String taskType) {
        return ResultUtils.success(taskService.listStatus(dsId, taskType));
    }

    /** 接口3 采集任务列表 */
    @Operation(summary = "采集任务列表")
    @GetMapping("/task/list")
    public BaseResponse<PageResult> list(@RequestParam(value = "page", required = false) Integer page,
                                         @RequestParam(value = "pageSize", required = false) Integer pageSize,
                                         @RequestParam(value = "ds_id", required = false) String dsId,
                                         @RequestParam(value = "status", required = false) String status) {
        int p = page == null || page < 1 ? 1 : page;
        // 契约校验：page>=1，pageSize 1~200（下限取 1，避免 0/负数触发 MP 全量无分页）
        int ps = pageSize == null ? 20 : Math.max(1, Math.min(pageSize, 200));
        return ResultUtils.success(taskService.pageList(dsId, status, p, ps));
    }

    /** 接口4 暂停采集任务 */
    @Operation(summary = "暂停采集任务")
    @PostMapping("/task/pause")
    public BaseResponse<PauseResultVO> pause(@RequestBody CrawlPauseResumeDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        KbCrawlerTaskService.PauseResumeResult result = taskService.pause(dto.getDsId());
        return ResultUtils.success(new PauseResultVO(result.pausedCount(), result.runningIgnored()));
    }

    /** 接口5 恢复采集任务 */
    @Operation(summary = "恢复采集任务")
    @PostMapping("/task/resume")
    public BaseResponse<ResumeResultVO> resume(@RequestBody CrawlPauseResumeDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        KbCrawlerTaskService.PauseResumeResult result = taskService.resume(dto.getDsId());
        return ResultUtils.success(new ResumeResultVO(result.resumedCount()));
    }

    /** 接口6 运行时 SQL 上报 */
    @Operation(summary = "运行时 SQL 上报")
    @PostMapping("/runtime/report")
    public BaseResponse<RuntimeReportResultVO> report(@RequestBody RuntimeReportDTO dto) {
        return ResultUtils.success(runtimeCollectService.consumeRuntimeEvents(dto));
    }

    /** 接口7 发版触发变更检测 */
    @Operation(summary = "发版触发变更检测")
    @PostMapping("/change/release-trigger")
    public BaseResponse<ChangeDetectResultVO> releaseTrigger(@RequestBody ReleaseTriggerDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        String evidence = dto.getReleaseTag() == null || dto.getReleaseTag().isBlank()
                ? "release-trigger" : "release_tag=" + dto.getReleaseTag();
        return ResultUtils.success(changeDetectService.detectChanges(dto.getDsId(), evidence));
    }
}
