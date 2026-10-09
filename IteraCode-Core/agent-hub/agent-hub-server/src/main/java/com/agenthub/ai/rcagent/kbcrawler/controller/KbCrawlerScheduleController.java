package com.agenthub.ai.rcagent.kbcrawler.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.rcagent.kbcrawler.dto.KbCrawlScheduleDTO;
import com.agenthub.ai.rcagent.kbcrawler.service.KbCrawlerTaskService;
import com.agenthub.ai.rcagent.kbcrawler.vo.KbCrawlScheduleVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集调度配置 API（接口1/2）：查询/修改数据源 SCHEMA_INCR 任务的任务级 cron 调度。
 *
 * <p>独立于既有 {@code KbCrawlerController}（保持 7 个既有接口零变更）；按要求本类方法不加 {@code @Loggable}。</p>
 */
@Tag(name = "KbCrawlerScheduleController", description = "知识自动采集管线-调度配置接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/kb-crawler")
public class KbCrawlerScheduleController {

    private final KbCrawlerTaskService taskService;

    public KbCrawlerScheduleController(KbCrawlerTaskService taskService) {
        this.taskService = taskService;
    }

    /** 接口1 查询采集调度配置（只读，不自动登记） */
    @Operation(summary = "查询采集调度配置")
    @GetMapping("/schedule")
    public BaseResponse<KbCrawlScheduleVO> getSchedule(@RequestParam("ds_id") String dsId) {
        return ResultUtils.success(taskService.getSchedule(dsId));
    }

    /** 接口2 修改采集调度配置（写入 schedule_cron 并重算 next_run_time） */
    @Operation(summary = "修改采集调度配置")
    @PutMapping("/schedule")
    public BaseResponse<KbCrawlScheduleVO> updateSchedule(@RequestBody KbCrawlScheduleDTO dto) {
        ThrowUtils.throwIf(dto == null, new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数不能为空"));
        return ResultUtils.success(taskService.updateSchedule(dto.getDsId(), dto.getScheduleCron()));
    }
}
