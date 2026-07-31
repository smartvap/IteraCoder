package com.agenthub.ai.base.service;

import com.agenthub.ai.base.entity.TokenUsageDetail;
import com.baomidou.mybatisplus.extension.service.IService;

public interface TokenUsageService extends IService<TokenUsageDetail> {
    /**
     * 异步记录 token 使用量（写明细表 + 更新聚合表）
     * @param userId     登录用户 ID（匿名时为 null）
     * @param ipAddress  客户端 IP
     * @param modelName  模型名称
     * @param promptTokens 输入 token 数
     * @param completionTokens 输出 token 数
     * @param totalDurationMs 耗时 ms
     * @param status     1=成功 0=失败
     */
    void recordAsync(Long userId, String ipAddress, String modelName,
                     int promptTokens, int completionTokens, long totalDurationMs, int status);
}
