package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 置信度正面信号响应 VO（接口16）。
 */
@Data
public class KbConfidencePositiveVO {

    private Long objectId;

    /** 更新前置信度 */
    private BigDecimal oldConfidence;

    /** 更新后置信度 */
    private BigDecimal newConfidence;

    /** 累计正面命中 */
    private Integer hitCount;

    /** 是否幂等去重（true=本次未重复累加） */
    private Boolean duplicate;
}
