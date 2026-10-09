package com.agenthub.ai.rcagent.feedback.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 触发分析结果 VO（接口5 data）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackAnalyzeVO {

    private Long flowId;

    /** SUGGESTING/REVIEW_CREATED/MANUAL_REQUIRED/ANALYZE_FAILED */
    private String analyzeStatus;

    /** 更新后闭环状态 */
    private String status;

    /** 修改类型 */
    private String changeType;

    /** 类型判定置信度 */
    private java.math.BigDecimal changeConfidence;

    /** 创建出的审核任务 id 列表 */
    private List<Long> reviewTaskIds;
}
