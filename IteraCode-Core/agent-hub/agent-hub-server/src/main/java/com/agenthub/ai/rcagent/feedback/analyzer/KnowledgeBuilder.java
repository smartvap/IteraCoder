package com.agenthub.ai.rcagent.feedback.analyzer;

import com.agenthub.ai.rcagent.feedback.constant.ChangeType;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackType;
import com.agenthub.ai.rcagent.feedback.constant.SuggestionOperation;
import com.agenthub.ai.rcagent.feedback.dto.KnowledgeRef;
import com.agenthub.ai.rcagent.feedback.model.KnowledgeSuggestion;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KnowledgeBuilder：修改类型 → 结构化修正建议生成（FR-BE-003/BR-002）。
 *
 * <p>规则（BR-002）：字段错误 → kb_column；JOIN 错误 → kb_relation；语义/模板 →
 * kb_sql_template；指标口径/时间 → kb_metric；概念/FAQ 补充 → kb_business_concept +
 * 库 B（llm-wiki）。若请求引用（relatedKnowledge）提供目标对象 id，则 operation=UPDATE
 * 并回填 objectId；否则 operation=NEW 视为“新知识采集”建议（经审核通过后 writeback 再
 * 决定建库，不直接写 kb_*，45007 语义）。<b>patch/suggested_change 属审核建议持久化
 * 内容（可含修正 SQL），日志/响应禁止原文外溢</b>。</p>
 */
@Slf4j
@Component
public class KnowledgeBuilder {

    /** 生成建议（可多条，按 priority 排序由调用方持久化） */
    public List<KnowledgeSuggestion> build(String dsId, String question, String generatedSql,
                                           String modifiedSql, String userComment,
                                           String feedbackType, String changeType,
                                           List<KnowledgeRef> relatedKnowledge,
                                           DiffSummary diff, String diffEvidence) {
        List<KnowledgeSuggestion> suggestions = new ArrayList<>();
        String type = changeType == null ? ChangeType.OTHER.getValue() : changeType;

        // 说明/概念补充类反馈 → 建议由人工/LLM 判定为 CONCEPT_SUPPLEMENT 或 EXPLANATION
        if (FeedbackType.EXPLANATION.getValue().equalsIgnoreCase(feedbackType)) {
            if (hasAttributionConceptHint(userComment)) {
                suggestions.add(buildConceptSupplement(dsId, question, userComment,
                        relatedKnowledge, diffEvidence));
            }
            return suggestions;
        }

        switch (type) {
            case "SQL_ERROR":
            case "SEMANTIC_ERROR":
            case "TIME_RANGE_ERROR":
                addIfPresent(suggestions, buildSqlTemplate(dsId, question, generatedSql, modifiedSql,
                        userComment, type, relatedKnowledge, diff, diffEvidence));
                break;
            case "FIELD_ERROR":
                addIfPresent(suggestions, buildColumn(dsId, question, modifiedSql, userComment,
                        relatedKnowledge, diff, diffEvidence));
                break;
            case "JOIN_ERROR":
                addIfPresent(suggestions, buildRelation(dsId, question, userComment,
                        relatedKnowledge, diff, diffEvidence));
                break;
            case "AGG_ERROR":
            case "ATTRIBUTION_CALIBER":
                addIfPresent(suggestions, buildMetric(dsId, question, userComment,
                        relatedKnowledge, diffEvidence));
                break;
            case "ATTRIBUTION_CAUSE":
                addIfPresent(suggestions, buildConceptSupplement(dsId, question, userComment,
                        relatedKnowledge, diffEvidence));
                break;
            case "ATTRIBUTION_SCOPE":
                addIfPresent(suggestions, buildConceptSupplement(dsId, question, userComment,
                        relatedKnowledge, diffEvidence));
                break;
            case "CONCEPT_SUPPLEMENT":
                addIfPresent(suggestions, buildConceptSupplement(dsId, question, userComment,
                        relatedKnowledge, diffEvidence));
                break;
            default:
                // OTHER：无法自动映射，无建议（由服务层决定 MANUAL_REQUIRED）
                break;
        }
        return suggestions;
    }

    private static void addIfPresent(List<KnowledgeSuggestion> suggestions,
                                     KnowledgeSuggestion suggestion) {
        if (suggestion != null) {
            suggestions.add(suggestion);
        }
    }

    private KnowledgeSuggestion baseSuggestion(String question, String evidence) {
        KnowledgeSuggestion s = new KnowledgeSuggestion();
        s.setEvidence(FeedbackSafeText.truncate(evidence, 200));
        s.setPriority(1);
        if (question != null && !question.isBlank()) {
            s.setTargetDesc(FeedbackSafeText.truncate(question, 100));
        }
        return s;
    }

    /** 定位引用对象 id：relatedKnowledge 中类型匹配者 */
    private Long resolveRefId(String targetType, List<KnowledgeRef> relatedKnowledge) {
        if (relatedKnowledge == null) {
            return null;
        }
        for (KnowledgeRef ref : relatedKnowledge) {
            if (ref == null) {
                continue;
            }
            if (targetType.equalsIgnoreCase(ref.getTargetObjectType())
                    && ref.getObjectId() != null) {
                return ref.getObjectId();
            }
        }
        return null;
    }

    private boolean hasRef(String targetType, List<KnowledgeRef> relatedKnowledge) {
        return resolveRefId(targetType, relatedKnowledge) != null;
    }

    private KnowledgeSuggestion buildSqlTemplate(String dsId, String question,
                                                 String generatedSql, String modifiedSql,
                                                 String userComment, String changeType,
                                                 List<KnowledgeRef> relatedKnowledge,
                                                 DiffSummary diff, String evidence) {
        KnowledgeSuggestion s = baseSuggestion(question, evidence);
        s.setTargetObjectType("SQL_TEMPLATE");
        s.setRepo("A");
        Long refId = resolveRefId("SQL_TEMPLATE", relatedKnowledge);
        if (refId != null) {
            s.setTargetObjectId(refId);
            s.setOperation(SuggestionOperation.UPDATE.getValue());
            s.setTaskType("FEEDBACK_CORRECTION");
        } else {
            s.setOperation(SuggestionOperation.NEW.getValue());
            s.setTaskType("TEMPLATE_PROMOTE");
        }
        JSONObject patch = new JSONObject();
        patch.put("db_type", "MYSQL");
        String normSql = modifiedSql != null && !modifiedSql.isBlank() ? modifiedSql : generatedSql;
        if (normSql != null) {
            patch.put("normalized_sql", normSql.trim());
        }
        if (userComment != null && !userComment.isBlank()) {
            patch.put("biz_action", FeedbackSafeText.truncate(userComment, 200));
        }
        if (question != null && !question.isBlank()) {
            patch.put("sample_questions", List.of(FeedbackSafeText.truncate(question, 200)));
        }
        if (diff != null && !diff.getChangedTables().isEmpty()) {
            patch.put("involved_tables", diff.getChangedTables());
        }
        JSONObject change = new JSONObject();
        change.put("operation", s.getOperation());
        change.put("patch", patch);
        s.setSuggestedChange(JSON.toJSONString(change));
        s.setTargetDesc("SQL 模板修正建议: " + changeType);
        return s;
    }

    private KnowledgeSuggestion buildColumn(String dsId, String question, String modifiedSql,
                                            String userComment,
                                            List<KnowledgeRef> relatedKnowledge,
                                            DiffSummary diff, String evidence) {
        KnowledgeSuggestion s = baseSuggestion(question, evidence);
        s.setTargetObjectType("COLUMN");
        s.setRepo("A");
        Long refId = resolveRefId("COLUMN", relatedKnowledge);
        if (refId != null) {
            s.setTargetObjectId(refId);
            s.setOperation(SuggestionOperation.UPDATE.getValue());
        } else {
            s.setOperation(SuggestionOperation.NEW.getValue());
        }
        s.setTaskType("FEEDBACK_CORRECTION");
        JSONObject patch = new JSONObject();
        if (diff != null && !diff.getChangedColumns().isEmpty()) {
            String first = diff.getChangedColumns().get(0);
            int dot = first.indexOf('.');
            if (dot > 0) {
                patch.put("table_name", first.substring(0, dot));
                patch.put("column_name", first.substring(dot + 1));
            } else {
                patch.put("column_name", first);
            }
        }
        if (userComment != null && !userComment.isBlank()) {
            patch.put("comment_text", FeedbackSafeText.truncate(userComment, 200));
        }
        JSONObject change = new JSONObject();
        change.put("operation", s.getOperation());
        change.put("patch", patch);
        s.setSuggestedChange(JSON.toJSONString(change));
        s.setTargetDesc("字段语义/注释修正建议");
        return s;
    }

    private KnowledgeSuggestion buildRelation(String dsId, String question, String userComment,
                                              List<KnowledgeRef> relatedKnowledge,
                                              DiffSummary diff, String evidence) {
        // module-004 saveRelation 必填 table_a/table_b：先从 AST diff 的 JOIN 关联键变化中
        // 还原关联表对与关联列，否则该建议回写恒失败
        RelationPair pair = resolveRelationPair(diff);
        if (pair == null) {
            // 无法定位关联表/键（parse 降级/无 JOIN 列信息）：不产出必败回写的 RELATION 建议，
            // 交由上层转 MANUAL_REQUIRED/人工补全（45007 语义，避免 writeback 无限 FAILED）
            log.info("feedback: JOIN_ERROR 无法从 diff 定位关联表对，转人工采集（dsId={}）",
                    dsId == null ? "" : dsId);
            return null;
        }
        KnowledgeSuggestion s = baseSuggestion(question, evidence);
        s.setTargetObjectType("RELATION");
        s.setRepo("A");
        Long refId = resolveRefId("RELATION", relatedKnowledge);
        if (refId != null) {
            s.setTargetObjectId(refId);
            s.setOperation(SuggestionOperation.UPDATE.getValue());
        } else {
            s.setOperation(SuggestionOperation.NEW.getValue());
        }
        s.setTaskType("RELATION_SUGGESTION");
        JSONObject patch = new JSONObject();
        patch.put("relation_type", "REFERENCE");
        patch.put("table_a", pair.tableA());
        patch.put("column_a", pair.columnA());
        patch.put("table_b", pair.tableB());
        patch.put("column_b", pair.columnB());
        // join_fields 二维数组与 module-004 saveRelation（KbRelationSaveDTO.joinFields）对齐
        if (pair.columnA() != null && pair.columnB() != null) {
            patch.put("join_fields", List.of(List.of(
                    pair.tableA() + "." + pair.columnA(), pair.tableB() + "." + pair.columnB())));
        }
        if (userComment != null && !userComment.isBlank()) {
            patch.put("join_hint", FeedbackSafeText.truncate(userComment, 200));
        }
        JSONObject change = new JSONObject();
        change.put("operation", s.getOperation());
        change.put("patch", patch);
        s.setSuggestedChange(JSON.toJSONString(change));
        s.setTargetDesc("表关系修正建议: " + pair.tableA() + " ↔ " + pair.tableB());
        return s;
    }

    /**
     * 从 AST diff 的 JOIN 关联键变化还原关联表对（{@code table.column}）。
     *
     * <p>候选顺序：先取「发生变更且修正 SQL 仍在使用」的关联键（即本次真正被修正的关联），
     * 再按修正 SQL 中其余 JOIN 关联键补齐关联对另一侧；前两个不同表构成 table_a/table_b，
     * 关联列取该表在候选中的键列。无法定位返回 null。</p>
     */
    private RelationPair resolveRelationPair(DiffSummary diff) {
        if (diff == null) {
            return null;
        }
        List<String> changedKeys = diff.getChangedJoinColumns() == null
                ? new ArrayList<>() : diff.getChangedJoinColumns();
        List<String> modKeys = diff.getModJoinColumns() == null
                ? new ArrayList<>() : diff.getModJoinColumns();
        Set<String> modSet = new LinkedHashSet<>(modKeys);
        List<String> candidates = new ArrayList<>();
        // 1. 被修正且仍保留在修正 SQL 中的关联键（代表修正后的正确关联）
        for (String key : changedKeys) {
            if (modSet.contains(key) && !candidates.contains(key)) {
                candidates.add(key);
            }
        }
        // 2. 修正 SQL 其余 JOIN 关联键（还原关联对另一侧）
        for (String key : modKeys) {
            if (!candidates.contains(key)) {
                candidates.add(key);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Map<String, String> tableToCol = new LinkedHashMap<>();
        for (String key : candidates) {
            int dot = key.indexOf('.');
            if (dot <= 0 || dot == key.length() - 1) {
                continue;
            }
            String table = key.substring(0, dot);
            if ("?".equals(table)) {
                continue;
            }
            tableToCol.putIfAbsent(table, key.substring(dot + 1));
        }
        if (tableToCol.size() < 2) {
            return null;
        }
        var it = tableToCol.entrySet().iterator();
        Map.Entry<String, String> first = it.next();
        Map.Entry<String, String> second = it.next();
        return new RelationPair(first.getKey(), first.getValue(), second.getKey(), second.getValue());
    }

    /** 关联表对（含建议关联键列，供 kb_relation 回写） */
    private record RelationPair(String tableA, String columnA, String tableB, String columnB) {
    }

    private KnowledgeSuggestion buildMetric(String dsId, String question, String userComment,
                                            List<KnowledgeRef> relatedKnowledge, String evidence) {
        KnowledgeSuggestion s = baseSuggestion(question, evidence);
        s.setTargetObjectType("METRIC");
        s.setRepo("A");
        Long refId = resolveRefId("METRIC", relatedKnowledge);
        if (refId != null) {
            s.setTargetObjectId(refId);
            s.setOperation(SuggestionOperation.UPDATE.getValue());
            s.setTaskType("METRIC_DEFINITION");
        } else {
            s.setOperation(SuggestionOperation.NEW.getValue());
            s.setTaskType("METRIC_DEFINITION");
        }
        JSONObject patch = new JSONObject();
        if (question != null && !question.isBlank()) {
            patch.put("metric_name", FeedbackSafeText.truncate(question, 64));
        }
        if (userComment != null && !userComment.isBlank()) {
            patch.put("definition", FeedbackSafeText.truncate(userComment, 200));
        }
        JSONObject change = new JSONObject();
        change.put("operation", s.getOperation());
        change.put("patch", patch);
        s.setSuggestedChange(JSON.toJSONString(change));
        s.setTargetDesc("指标口径/聚合修正建议");
        return s;
    }

    /** 概念/FAQ 补充 → kb_business_concept（A） + 库 B 沉淀 */
    private KnowledgeSuggestion buildConceptSupplement(String dsId, String question,
                                                       String userComment,
                                                       List<KnowledgeRef> relatedKnowledge,
                                                       String evidence) {
        KnowledgeSuggestion s = baseSuggestion(question, evidence);
        s.setTargetObjectType("BUSINESS_CONCEPT");
        s.setRepo("A+B");
        Long refId = resolveRefId("BUSINESS_CONCEPT", relatedKnowledge);
        if (refId != null) {
            s.setTargetObjectId(refId);
            s.setOperation(SuggestionOperation.UPDATE.getValue());
        } else {
            s.setOperation(SuggestionOperation.NEW.getValue());
        }
        s.setTaskType("CONCEPT_DEFINITION");
        JSONObject patch = new JSONObject();
        if (question != null && !question.isBlank()) {
            patch.put("concept_name", FeedbackSafeText.truncate(question, 64));
        }
        if (userComment != null && !userComment.isBlank()) {
            patch.put("definition", FeedbackSafeText.truncate(userComment, 200));
        }
        JSONObject change = new JSONObject();
        change.put("operation", s.getOperation());
        change.put("patch", patch);
        // 概念补充同时提供库 B FAQ/概念页内容建议
        JSONObject bContent = new JSONObject();
        bContent.put("page_title", FeedbackSafeText.truncate(question, 64));
        bContent.put("page_content", buildConceptPageContent(question, userComment));
        JSONObject change2 = new JSONObject();
        change2.put("operation", s.getOperation());
        change2.put("patch", patch);
        change2.put("b_content", bContent);
        s.setSuggestedChange(JSON.toJSONString(change2));
        s.setTargetDesc("业务概念/FAQ 补充建议");
        return s;
    }

    private String buildConceptPageContent(String question, String userComment) {
        StringBuilder sb = new StringBuilder("# ");
        sb.append(question == null || question.isBlank() ? "概念补充" : question.trim());
        sb.append("\n\n");
        if (userComment != null && !userComment.isBlank()) {
            sb.append(userComment.trim());
        } else {
            sb.append("由用户反馈补充的概念/口径说明（经人工审核沉淀）。");
        }
        String text = sb.toString();
        return text.length() > 5000 ? text.substring(0, 5000) : text;
    }

    /** 是否存在概念/口径补充提示词 */
    private boolean hasAttributionConceptHint(String userComment) {
        if (userComment == null) {
            return false;
        }
        String c = userComment.toLowerCase();
        return c.contains("概念") || c.contains("口径") || c.contains("解释")
                || c.contains("definition") || c.contains("faq");
    }
}
