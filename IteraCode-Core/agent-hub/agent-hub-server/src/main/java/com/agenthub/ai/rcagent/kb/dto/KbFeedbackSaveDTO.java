package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口25 反馈落库入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbFeedbackSaveDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 关联会话/工作流 thread_id */
    private String sessionId;

    /** 原始问题（必填） */
    private String question;

    /** 生成 SQL */
    private String generatedSql;

    /** 修正 SQL */
    private String modifiedSql;

    /** 修改类型（feedback-loop 判定） */
    private String changeType;

    /** 反馈类型 POSITIVE/NEGATIVE/CORRECTION/EXPLANATION */
    private String feedbackType;

    /** 用户反馈文本 */
    private String userComment;

    /** 审核结论（联动写入，可空） */
    private String reviewConclusion;

    /** 状态，默认 PENDING */
    private String status;
}
