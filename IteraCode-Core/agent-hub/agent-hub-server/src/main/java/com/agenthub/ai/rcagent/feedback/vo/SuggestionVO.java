package com.agenthub.ai.rcagent.feedback.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 建议视图对象（suggestions JSON 数组元素，对外安全字段）。
 *
 * <p>targetObject 承载 {objectId, desc}；evidence 为审计摘要（feedbackId/diff 引用），
 * 不含 SQL 原文。suggested_change 修正内容不直接对外下发（写回执行由 KnowledgeBuilder
 * 内部快照承载，AGENTS.md 5.4 禁原文外溢）。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SuggestionVO {

    /** 目标对象类型 COLUMN/RELATION/SQL_TEMPLATE/METRIC/BUSINESS_CONCEPT/UNKNOWN */
    private String targetObjectType;

    /** 目标对象（{objectId, desc}） */
    private TargetObjectVO targetObject;

    /** 操作 NEW/UPDATE/DEPRECATE/PROMOTE */
    private String operation;

    /** 回写目标 A/B/A+B */
    private String repo;

    /** 证据摘要：feedbackId + diff 引用 + review task id（不含原文） */
    private String evidence;

    /** 优先级 */
    private Integer priority;

    /** 目标对象 {objectId, desc} 结构 */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TargetObjectVO {
        private Long objectId;
        private String desc;

        public static TargetObjectVO of(Long objectId, String desc) {
            TargetObjectVO vo = new TargetObjectVO();
            vo.setObjectId(objectId);
            vo.setDesc(desc);
            return vo;
        }
    }
}
