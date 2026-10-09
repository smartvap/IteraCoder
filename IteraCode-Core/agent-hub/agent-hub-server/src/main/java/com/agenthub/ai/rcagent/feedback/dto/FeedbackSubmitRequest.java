package com.agenthub.ai.rcagent.feedback.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 提交反馈入参（接口1 POST /api/v1/rca/feedback）。
 *
 * <p>接收正确性评价/SQL 合理性 + 用户修正 SQL/口径解释补充/归因反馈。
 * 主载荷全为强类型字段；attributionContext 属契约允许的弱类型 JSON 快照
 * （归因上下文 metricName/dimension/caliberText/rootCause），Service 内校验长度/脱敏。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackSubmitRequest {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 关联会话/工作流 thread_id */
    private String sessionId;

    /** 原始问题（必填，≤4000） */
    private String question;

    /** 系统生成的 SQL */
    private String generatedSql;

    /** 用户修正 SQL */
    private String modifiedSql;

    /** 反馈类型 POSITIVE/NEGATIVE/CORRECTION/EXPLANATION */
    private String feedbackType;

    /** 用户文本反馈/解释补充（≤2000） */
    private String userComment;

    /** 本次回答引用的知识对象（可选，供置信度信号定位） */
    private List<KnowledgeRef> relatedKnowledge;

    /** 归因反馈上下文（弱类型 JSON 快照：metricName/dimension/caliberText/rootCause 等） */
    private Map<String, Object> attributionContext;
}
