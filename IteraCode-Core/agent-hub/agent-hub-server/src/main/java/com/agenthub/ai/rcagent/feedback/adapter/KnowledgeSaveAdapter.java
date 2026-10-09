package com.agenthub.ai.rcagent.feedback.adapter;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackErrorCode;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.rcagent.kb.constant.KbKnowledgeSource;
import com.agenthub.ai.rcagent.kb.constant.KbKnowledgeStatus;
import com.agenthub.ai.rcagent.kb.dto.KbColumnSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbConceptSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbMetricSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbRelationSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbTemplateSaveDTO;
import com.agenthub.ai.rcagent.kb.service.KbKnowledgeService;
import com.agenthub.ai.rcagent.kb.vo.KbSaveResultVO;
import com.agenthub.ai.rcagent.feedback.model.KnowledgeSuggestion;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * module-004 kb-knowledge（知识对象存储）→ feedback-loop 交互适配器（brownfield 边界）。
 *
 * <p>审核通过后的库 A 回写经 {@link KbKnowledgeService#saveXxx} 进程内调用
 * （knowledge save 为 upsert：同唯一键命中即更新），evidence 统一携带
 * feedback id + review task id（BR-003 可溯源）。本适配器不直接 UPDATE 004 表，
 * 只构造 module-004 入参 DTO。patch 键 snake_case 与 module-004 DTO 对齐。</p>
 */
@Slf4j
@Component
public class KnowledgeSaveAdapter {

    private final KbKnowledgeService knowledgeService;

    public KnowledgeSaveAdapter(KbKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 库 A 回写：按建议目标类型执行 module-004 knowledge save。
     *
     * @return save 结果；建议不可回写（UNKNOWN/缺 patch）返回 null
     */
    public KbSaveResultVO applyAWriteback(String dsId, KnowledgeSuggestion suggestion, String evidence) {
        if (suggestion == null || !suggestion.actionable()) {
            return null;
        }
        try {
            JSONObject patch = suggestion.patchObject();
            return switch (suggestion.getTargetObjectType()) {
                case "SQL_TEMPLATE" -> saveTemplate(dsId, patch, evidence);
                case "COLUMN" -> saveColumn(dsId, patch, evidence);
                case "RELATION" -> saveRelation(dsId, patch, evidence);
                case "METRIC" -> saveMetric(dsId, patch, evidence);
                case "BUSINESS_CONCEPT" -> saveConcept(dsId, patch, evidence);
                default -> null;
            };
        } catch (RuntimeException e) {
            // module-004 knowledge save 依赖异常统一收敛 45005（NFR-BE-003），
            // 由 writeback 逐条兜底记录失败原因，日志不落建议原文
            log.warn("feedback: module-004 知识保存失败 dsId={} type={}: {}",
                    dsId == null ? "" : dsId, suggestion.getTargetObjectType(),
                    FeedbackSafeText.truncate(e.getMessage(), 200));
            throw new BusinessException(FeedbackErrorCode.DEPENDENCY_CALL_FAILED,
                    "知识库保存服务不可用，请稍后重试");
        }
    }

    private KbSaveResultVO saveTemplate(String dsId, JSONObject patch, String evidence) {
        String normalizedSql = patch.getString("normalized_sql");
        if (normalizedSql == null || normalizedSql.isBlank()) {
            return null;
        }
        KbTemplateSaveDTO dto = new KbTemplateSaveDTO();
        dto.setDsId(dsId);
        dto.setDbType(patch.getString("db_type"));
        dto.setBizAction(patch.getString("biz_action"));
        dto.setNormalizedSql(normalizedSql);
        dto.setInvolvedTables(stringList(patch.get("involved_tables")));
        dto.setSampleQuestions(stringList(patch.get("sample_questions")));
        dto.setFreq(1);
        dto.setKnowledgeSource(KbKnowledgeSource.HUMAN.getValue());
        dto.setConfidence(new BigDecimal("1.000"));
        dto.setVerified(1);
        dto.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
        dto.setEvidence(evidence);
        return knowledgeService.saveTemplate(dto);
    }

    private KbSaveResultVO saveColumn(String dsId, JSONObject patch, String evidence) {
        String columnName = patch.getString("column_name");
        if (columnName == null || columnName.isBlank()) {
            return null;
        }
        KbColumnSaveDTO dto = new KbColumnSaveDTO();
        dto.setDsId(dsId);
        dto.setSchemaName(patch.getString("schema_name"));
        dto.setTableName(patch.getString("table_name"));
        dto.setColumnName(columnName);
        dto.setDataType(patch.getString("data_type"));
        dto.setIsPk(patch.getInteger("is_pk"));
        dto.setIsFk(patch.getInteger("is_fk"));
        dto.setCommentText(patch.getString("comment_text"));
        dto.setSemanticTags(stringList(patch.get("semantic_tags")));
        dto.setKnowledgeSource(KbKnowledgeSource.HUMAN.getValue());
        dto.setConfidence(new BigDecimal("1.000"));
        dto.setVerified(1);
        dto.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
        dto.setEvidence(evidence);
        return knowledgeService.saveColumn(dto);
    }

    private KbSaveResultVO saveRelation(String dsId, JSONObject patch, String evidence) {
        String tableA = patch.getString("table_a");
        String tableB = patch.getString("table_b");
        if (tableA == null || tableA.isBlank() || tableB == null || tableB.isBlank()) {
            return null;
        }
        KbRelationSaveDTO dto = new KbRelationSaveDTO();
        dto.setDsId(dsId);
        dto.setDbType(patch.getString("db_type"));
        dto.setTableA(tableA);
        dto.setColumnA(patch.getString("column_a"));
        dto.setTableB(tableB);
        dto.setColumnB(patch.getString("column_b"));
        dto.setJoinFields(nestedList(patch.get("join_fields")));
        dto.setRelationType(patch.getString("relation_type") == null
                ? "REFERENCE" : patch.getString("relation_type"));
        dto.setRelationSource("HUMAN_FEEDBACK");
        dto.setKnowledgeSource(KbKnowledgeSource.HUMAN.getValue());
        dto.setConfidence(new BigDecimal("1.000"));
        dto.setVerified(1);
        dto.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
        dto.setEvidence(evidence);
        return knowledgeService.saveRelation(dto);
    }

    private KbSaveResultVO saveMetric(String dsId, JSONObject patch, String evidence) {
        String metricName = patch.getString("metric_name");
        if (metricName == null || metricName.isBlank()) {
            return null;
        }
        KbMetricSaveDTO dto = new KbMetricSaveDTO();
        dto.setDsId(dsId);
        dto.setMetricName(metricName);
        dto.setDefinition(patch.getString("definition"));
        dto.setCalculationRule(patch.getString("calculation_rule"));
        dto.setRelatedTables(stringList(patch.get("related_tables")));
        dto.setRelatedColumns(stringList(patch.get("related_columns")));
        dto.setSpaceName(patch.getString("space_name"));
        dto.setKnowledgeSource(KbKnowledgeSource.HUMAN.getValue());
        dto.setConfidence(new BigDecimal("1.000"));
        dto.setVerified(1);
        dto.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
        dto.setEvidence(evidence);
        return knowledgeService.saveMetric(dto);
    }

    private KbSaveResultVO saveConcept(String dsId, JSONObject patch, String evidence) {
        String conceptName = patch.getString("concept_name");
        if (conceptName == null || conceptName.isBlank()) {
            return null;
        }
        KbConceptSaveDTO dto = new KbConceptSaveDTO();
        dto.setDsId(dsId);
        dto.setConceptName(conceptName);
        dto.setDefinition(patch.getString("definition"));
        dto.setSynonyms(stringList(patch.get("synonyms")));
        dto.setRelatedTables(stringList(patch.get("related_tables")));
        dto.setRelatedColumns(stringList(patch.get("related_columns")));
        dto.setSpaceName(patch.getString("space_name"));
        dto.setKnowledgeSource(KbKnowledgeSource.HUMAN.getValue());
        dto.setConfidence(new BigDecimal("1.000"));
        dto.setVerified(1);
        dto.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
        dto.setEvidence(evidence);
        return knowledgeService.saveConcept(dto);
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return new ArrayList<>();
        }
        JSONArray arr = value instanceof JSONArray array ? array : JSON.parseArray(String.valueOf(value));
        return arr == null ? new ArrayList<>() : arr.toJavaList(String.class);
    }

    private static List<List<String>> nestedList(Object value) {
        List<List<String>> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        JSONArray outer = value instanceof JSONArray array ? array : JSON.parseArray(String.valueOf(value));
        if (outer == null) {
            return result;
        }
        for (int i = 0; i < outer.size(); i++) {
            JSONArray inner = outer.getJSONArray(i);
            if (inner != null) {
                result.add(inner.toJavaList(String.class));
            }
        }
        return result;
    }
}
