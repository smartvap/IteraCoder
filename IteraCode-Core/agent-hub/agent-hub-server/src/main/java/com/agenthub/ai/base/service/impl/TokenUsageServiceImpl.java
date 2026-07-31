package com.agenthub.ai.base.service.impl;

import com.agenthub.ai.base.entity.TokenUsageDetail;
import com.agenthub.ai.base.entity.TokenUsageSummary;
import com.agenthub.ai.base.mapper.TokenUsageDetailMapper;
import com.agenthub.ai.base.mapper.TokenUsageSummaryMapper;
import com.agenthub.ai.base.service.TokenUsageService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageServiceImpl extends ServiceImpl<TokenUsageDetailMapper, TokenUsageDetail>
        implements TokenUsageService {

    private final TokenUsageSummaryMapper summaryMapper;

    @Override
    @Async
    public void recordAsync(Long userId, String ipAddress, String modelName,
                            int promptTokens, int completionTokens, long totalDurationMs, int status) {
        try {
            // 1. 写明细表
            TokenUsageDetail detail = new TokenUsageDetail();
            detail.setUserId(userId);
            detail.setIpAddress(ipAddress);
            detail.setModelName(modelName);
            detail.setPromptTokens(promptTokens);
            detail.setCompletionTokens(completionTokens);
            detail.setTotalDurationMs(totalDurationMs);
            detail.setRequestTime(new Date());
            detail.setStatus(status);
            save(detail);

            // 2. 更新聚合表（按天）
            LocalDate today = LocalDate.now();
            Date statDate = Date.from(today.atStartOfDay(ZoneId.systemDefault()).toInstant());

            // 已登录用户：user_ip 聚合键
            if (userId != null) {
                upsertSummary("user_ip", userId + "_" + ipAddress, statDate,
                        promptTokens, completionTokens, totalDurationMs);
            }
            // 所有用户：ip_only 聚合键
            upsertSummary("ip_only", ipAddress, statDate,
                    promptTokens, completionTokens, totalDurationMs);

        } catch (Exception e) {
            log.error("Token 用量异步记录失败", e);
        }
    }

    private void upsertSummary(String statType, String statKey, Date statDate,
                               int promptTokens, int completionTokens, long durationMs) {
        TokenUsageSummary exist = summaryMapper.selectOne(
                new LambdaQueryWrapper<TokenUsageSummary>()
                        .eq(TokenUsageSummary::getStatType, statType)
                        .eq(TokenUsageSummary::getStatKey, statKey)
                        .eq(TokenUsageSummary::getStatDate, statDate)
        );
        if (exist != null) {
            exist.setTotalRequests(exist.getTotalRequests() + 1);
            exist.setTotalPromptTokens(exist.getTotalPromptTokens() + promptTokens);
            exist.setTotalCompletionTokens(exist.getTotalCompletionTokens() + completionTokens);
            exist.setTotalDurationMs(exist.getTotalDurationMs() + durationMs);
            summaryMapper.updateById(exist);
        } else {
            TokenUsageSummary s = new TokenUsageSummary();
            s.setStatType(statType);
            s.setStatKey(statKey);
            s.setStatDate(statDate);
            s.setTotalRequests(1);
            s.setTotalPromptTokens((long) promptTokens);
            s.setTotalCompletionTokens((long) completionTokens);
            s.setTotalDurationMs(durationMs);
            summaryMapper.insert(s);
        }
    }
}
