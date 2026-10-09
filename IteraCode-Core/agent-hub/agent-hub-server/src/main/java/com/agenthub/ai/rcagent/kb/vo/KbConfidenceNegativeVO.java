package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 置信度负面信号响应 VO（接口17）。
 */
@Data
public class KbConfidenceNegativeVO {

    private Long objectId;

    private BigDecimal oldConfidence;

    private BigDecimal newConfidence;

    /** 累计负面 */
    private Integer errorCount;

    /** 更新后状态（active/needs_review/draft） */
    private String status;

    /** 达阈值自动创建的审核任务 id */
    private Long reviewTaskId;

    /** 是否幂等去重 */
    private Boolean duplicate;
}
