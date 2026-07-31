package com.agenthub.ai.base.controller;

import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.base.entity.TokenUsageDetail;
import com.agenthub.ai.base.entity.TokenUsageSummary;
import com.agenthub.ai.base.mapper.TokenUsageDetailMapper;
import com.agenthub.ai.base.mapper.TokenUsageSummaryMapper;
import com.agenthub.ai.base.pojo.dto.TokenUsageQueryDTO;
import com.agenthub.ai.base.pojo.vo.TokenUsageVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController
@RequestMapping("/api/v1/stats/token")
@RequiredArgsConstructor
public class TokenUsageController {

    private final TokenUsageDetailMapper detailMapper;
    private final TokenUsageSummaryMapper summaryMapper;

    /** 获取当前用户/IP 今日用量 */
    @GetMapping("/usage")
    public TokenUsageVO getTodayUsage(ServerHttpRequest request) {
        Long userId = BaseContext.getCurrentId();
        String ip = getClientIp(request);
        LocalDate today = LocalDate.now();
        Date statDate = Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant());

        TokenUsageVO vo = new TokenUsageVO();
        LambdaQueryWrapper<TokenUsageSummary> qw = new LambdaQueryWrapper<>();
        qw.eq(TokenUsageSummary::getStatDate, statDate);

        // 已登录用户查 user_ip 聚合
        if (userId != null) {
            qw.eq(TokenUsageSummary::getStatType, "user_ip")
              .eq(TokenUsageSummary::getStatKey, userId + "_" + ip);
            TokenUsageSummary s = summaryMapper.selectOne(qw);
            if (s != null) {
                vo.setTotalRequests(s.getTotalRequests());
                vo.setTotalPromptTokens(s.getTotalPromptTokens());
                vo.setTotalCompletionTokens(s.getTotalCompletionTokens());
                vo.setTotalTokens(s.getTotalPromptTokens() + s.getTotalCompletionTokens());
                vo.setTotalDurationMs(s.getTotalDurationMs());
                return vo;
            }
        }

        // 匿名或 user_ip 未命中 → 查 ip_only
        qw = new LambdaQueryWrapper<>();
        qw.eq(TokenUsageSummary::getStatDate, statDate)
          .eq(TokenUsageSummary::getStatType, "ip_only")
          .eq(TokenUsageSummary::getStatKey, ip);
        TokenUsageSummary s = summaryMapper.selectOne(qw);
        if (s != null) {
            vo.setTotalRequests(s.getTotalRequests());
            vo.setTotalPromptTokens(s.getTotalPromptTokens());
            vo.setTotalCompletionTokens(s.getTotalCompletionTokens());
            vo.setTotalTokens(s.getTotalPromptTokens() + s.getTotalCompletionTokens());
            vo.setTotalDurationMs(s.getTotalDurationMs());
        }
        return vo;
    }

    /** 分页查询明细 */
    @GetMapping("/detail")
    public Page<TokenUsageDetail> getDetail(TokenUsageQueryDTO dto) {
        Page<TokenUsageDetail> page = new Page<>(dto.getPage(), dto.getPageSize());
        LambdaQueryWrapper<TokenUsageDetail> qw = new LambdaQueryWrapper<>();
        qw.orderByDesc(TokenUsageDetail::getRequestTime);
        return detailMapper.selectPage(page, qw);
    }

    /** 按模型统计今日 token 用量 */
    @GetMapping("/by-model")
    public List<Map<String, Object>> getByModel() {
        LocalDate today = LocalDate.now();
        Date start = Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date end = Date.from(today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());

        LambdaQueryWrapper<TokenUsageDetail> qw = new LambdaQueryWrapper<>();
        qw.ge(TokenUsageDetail::getRequestTime, start)
          .lt(TokenUsageDetail::getRequestTime, end)
          .eq(TokenUsageDetail::getStatus, 1);

        List<TokenUsageDetail> list = detailMapper.selectList(qw);

        // 按 modelName 聚合
        Map<String, long[]> map = new LinkedHashMap<>();
        for (TokenUsageDetail d : list) {
            String model = d.getModelName() != null ? d.getModelName() : "unknown";
            long[] stats = map.computeIfAbsent(model, k -> new long[4]);
            stats[0]++; // requests
            stats[1] += d.getPromptTokens();
            stats[2] += d.getCompletionTokens();
            stats[3] += d.getTotalDurationMs();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, long[]> e : map.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("modelName", e.getKey());
            item.put("requests", e.getValue()[0]);
            item.put("promptTokens", e.getValue()[1]);
            item.put("completionTokens", e.getValue()[2]);
            item.put("totalTokens", e.getValue()[1] + e.getValue()[2]);
            item.put("totalDurationMs", e.getValue()[3]);
            result.add(item);
        }
        return result;
    }

    @PostMapping("/sync")
    public String syncUsage(@RequestBody Map<String, Object> data, ServerHttpRequest request) {
        TokenUsageDetail detail = new TokenUsageDetail();
        detail.setUserId(BaseContext.getCurrentId());
        detail.setIpAddress(getClientIp(request));
        detail.setModelName((String) data.getOrDefault("modelName", "unknown"));
        detail.setPromptTokens(((Number) data.getOrDefault("promptTokens", 0)).intValue());
        detail.setCompletionTokens(((Number) data.getOrDefault("completionTokens", 0)).intValue());
        detail.setTotalDurationMs(((Number) data.getOrDefault("totalDurationMs", 0)).longValue());
        detail.setRequestTime(new Date());
        detail.setStatus(1);
        detailMapper.insert(detail);
        return "ok";
    }

    private String getClientIp(ServerHttpRequest request) {
        String ip = request.getHeaders().getFirst("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeaders().getFirst("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddress() != null
                    ? request.getRemoteAddress().getAddress().getHostAddress()
                    : "127.0.0.1";
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "127.0.0.1";
    }
}
