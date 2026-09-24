package com.agenthub.ai.datasource.exception;

import com.agenthub.ai.datasource.vo.HealthCheckResultVO;
import lombok.Getter;

/**
 * 启用前置健康检查未通过（43005）。
 *
 * <p>不继承 BusinessException：controller 需捕获并返回带诊断数据的 BaseResponse(43005, health, message)，
 * 而 GlobalExceptionHandler 对 BusinessException 统一返回无 data 结构。本类继承 RuntimeException 防止被误吞。</p>
 */
@Getter
public class DsHealthCheckFailedException extends RuntimeException {

    private final HealthCheckResultVO health;

    public DsHealthCheckFailedException(HealthCheckResultVO health, String message) {
        super(message);
        this.health = health;
    }
}
