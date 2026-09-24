package com.agenthub.ai.datasource.exception;

import com.agenthub.ai.datasource.vo.SpaceBindResultVO;
import lombok.Getter;

/**
 * llm-wiki 空间联动不可用（43006）。
 *
 * <p>降级保存 BIND_PENDING 但接口需返回错误码 43006 且 data 携带 SpaceBindResultVO 供前端提示；
 * 继承 RuntimeException 由 Controller 捕获构造带 data 的响应。</p>
 */
@Getter
public class DsSpaceLinkUnavailableException extends RuntimeException {

    private final SpaceBindResultVO result;

    public DsSpaceLinkUnavailableException(SpaceBindResultVO result, String message) {
        super(message);
        this.result = result;
    }
}
