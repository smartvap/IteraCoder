package com.agenthub.ai.base.exception;

import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * SSE 连接超时处理：AsyncRequestTimeoutException 是 SSE 长连接超时后的正常事件。
     * 不能返回 JSON 响应（Content-Type 已是 text/event-stream，无对应转换器）。
     * 返回 void 避免 HttpMessageNotWritableException。
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void asyncRequestTimeoutHandler(AsyncRequestTimeoutException e) {
        // SSE 连接超时，response 已提交，无需额外处理
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, e.getMessage());
    }


}