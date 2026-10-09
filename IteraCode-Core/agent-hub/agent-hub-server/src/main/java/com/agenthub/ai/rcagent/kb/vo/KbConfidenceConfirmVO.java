package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 置信度人工确认响应 VO（接口18）。
 */
@Data
public class KbConfidenceConfirmVO {

    private Long objectId;

    /** 恒 1.000 */
    private BigDecimal confidence;

    /** 恒 active */
    private String status;

    /** 恒 1 */
    private Integer verified;
}
