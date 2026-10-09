package com.agenthub.ai.rcagent.feedback.dto;

import lombok.Data;

/**
 * 知识对象引用（FeedbackSubmitRequest.relatedKnowledge 元素）。
 * 供置信度信号定位：本次回答引用的知识对象（module-004 TargetObjectType）。
 */
@Data
public class KnowledgeRef {

    /** 目标对象类型（COLUMN/RELATION/SQL_TEMPLATE/METRIC/BUSINESS_CONCEPT 等） */
    private String targetObjectType;

    /** 目标对象主键 */
    private Long objectId;
}
