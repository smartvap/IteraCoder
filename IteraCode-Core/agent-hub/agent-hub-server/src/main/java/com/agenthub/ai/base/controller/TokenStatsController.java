package com.agenthub.ai.base.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.base.mapper.TokenUsageDetailMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/stats-token")
@RequiredArgsConstructor
public class TokenStatsController {

    private final TokenUsageDetailMapper detailMapper;

    @GetMapping("/timeseries")
    public BaseResponse<List<Map<String, Object>>> timeseries(
            @RequestParam(defaultValue = "day") String granularity,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResultUtils.success(detailMapper.timeSeries(granularity, toDate(start), toDate(end)));
    }

    @GetMapping("/by-user")
    public BaseResponse<List<Map<String, Object>>> byUser(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResultUtils.success(detailMapper.rankByUser(toDate(start), toDate(end)));
    }

    @GetMapping("/by-ip")
    public BaseResponse<List<Map<String, Object>>> byIp(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResultUtils.success(detailMapper.rankByIp(toDate(start), toDate(end)));
    }

    @GetMapping("/by-model")
    public BaseResponse<List<Map<String, Object>>> byModel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResultUtils.success(detailMapper.distByModel(toDate(start), toDate(end)));
    }

    @GetMapping("/by-type")
    public BaseResponse<List<Map<String, Object>>> byType(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResultUtils.success(detailMapper.distBySource(toDate(start), toDate(end)));
    }

    @GetMapping("/summary")
    public BaseResponse<Map<String, Object>> summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ResultUtils.success(detailMapper.summary(toDate(start), toDate(end)));
    }

    private Date toDate(LocalDateTime t) {
        return t == null ? null : Date.from(t.atZone(ZoneId.systemDefault()).toInstant());
    }
}