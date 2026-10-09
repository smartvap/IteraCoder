package com.agenthub.ai.rcagent.feedback.model;

import com.agenthub.ai.rcagent.feedback.vo.SuggestionVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

/**
 * 修正建议内部快照（存储于 fb_feedback_flow.suggestions JSON 数组）。
 *
 * <p>字段与 data-model SuggestionSchema 对齐：targetObjectType/targetObject/
 * operation/suggested_change/repo/priority/evidence。其中 suggested_change 为
 * module-004 review task 兼容的 {operation, patch} JSON，patch 键采用 snake_case
 * （comment_text/definition/sample_questions/biz_action/... 与 module-004
 * applySuggestedPatch 白名单一致）。suggestions JSON 存库但对外 VO 只展示安全字段。</p>
 */
@Data
public class KnowledgeSuggestion {

    /** 目标对象类型 SQL_TEMPLATE/COLUMN/RELATION/METRIC/BUSINESS_CONCEPT/UNKNOWN */
    private String targetObjectType;

    /** 目标对象 id（未收录/新知识采集为 null） */
    private Long targetObjectId;

    /** 目标对象描述 */
    private String targetDesc;

    /** 操作 NEW/UPDATE/DEPRECATE/PROMOTE */
    private String operation;

    /** 审核任务类型（module-004 KbReviewTaskType） */
    private String taskType;

    /** 回写目标 A/B/A+B */
    private String repo;

    /** 优先级（越小越优先） */
    private Integer priority = 1;

    /** 证据摘要（feedbackId + diff 引用，不含 SQL 原文） */
    private String evidence;

    /** 结构化修正建议 {operation, patch} JSON（module-004 兼容） */
    private String suggestedChange;

    /** 是否有建议对象（非 UNKNOWN 且具备可写字段） */
    public boolean actionable() {
        return targetObjectType != null
                && !targetObjectType.isBlank()
                && !"UNKNOWN".equals(targetObjectType);
    }

    /** 转为对外 SuggestionVO（剔除 suggestedChange 修正原文，仅安全字段） */
    public SuggestionVO toVO() {
        SuggestionVO vo = new SuggestionVO();
        vo.setTargetObjectType(targetObjectType);
        vo.setOperation(operation);
        vo.setRepo(repo);
        vo.setEvidence(evidence);
        vo.setPriority(priority);
        vo.setTargetObject(SuggestionVO.TargetObjectVO.of(targetObjectId, targetDesc));
        return vo;
    }

    /** patch 提取：suggested_change JSON 中的 patch 对象 */
    public JSONObject patchObject() {
        if (suggestedChange == null || suggestedChange.isBlank()) {
            return new JSONObject();
        }
        try {
            JSONObject change = JSON.parseObject(suggestedChange);
            JSONObject patch = change == null ? null : change.getJSONObject("patch");
            return patch == null ? new JSONObject() : patch;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
