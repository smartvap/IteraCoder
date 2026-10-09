package com.agenthub.ai.rcagent.feedback.analyzer;

import com.agenthub.ai.rcagent.feedback.constant.ChangeType;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;

/**
 * 修改类型分类器（FR-BE-002）：确定性候选先行 + LLM 单次结构化输出归类兜底。
 *
 * <p>规则：diff 摘要中时间条件变化 → TIME_RANGE_ERROR；JOIN 变化 → JOIN_ERROR；
 * 聚合变化 → AGG_ERROR；表变化无列变化 → SEMANTIC_ERROR；列变化 → FIELD_ERROR。
 * 上述确定性命中置信 ≥0.85 直接返回；否则若可用 ChatModel 则单次结构化输出
 * {change_type, confidence, reason} 兜底；LLM 不可用或输出非法时返回 manualRequired=true
 * （服务层置 MANUAL_REQUIRED，不自动建审）。</p>
 */
@Slf4j
@Component
public class ChangeTypeClassifier {

    private static final BigDecimal HIGH_CONFIDENCE = new BigDecimal("0.85");

    private final ObjectProvider<ChatModel> chatModelProvider;

    public ChangeTypeClassifier(ObjectProvider<ChatModel> chatModelProvider) {
        this.chatModelProvider = chatModelProvider;
    }

    /**
     * 分类（反馈类型 + diff + 问题/备注 + 归因上下文）。
     *
     * @param feedbackType      POSITIVE/NEGATIVE/CORRECTION/EXPLANATION
     * @param diff              AST/文本 diff 摘要
     * @param question          原始问题（截断供 LLM 参考）
     * @param userComment       用户备注（截断）
     * @param attributionJson   归因反馈上下文 JSON（metricName/dimension/caliberText/rootCause），可空
     * @return 分类结果
     */
    public ChangeTypeResult classify(String feedbackType, DiffSummary diff,
                                     String question, String userComment,
                                     String attributionJson) {
        // 归因上下文驱动（ATTRIBUTION_SCOPE/CAUSE/CALIBER）：字段命中即高置信判定
        ChangeTypeResult attribution = attributionCandidates(attributionJson);
        if (attribution != null) {
            return attribution;
        }
        // 肯定反馈：不涉及知识修正
        if ("POSITIVE".equalsIgnoreCase(feedbackType)) {
            ChangeTypeResult positive = new ChangeTypeResult();
            positive.setChangeType(ChangeType.OTHER.getValue());
            positive.setConfidence(new BigDecimal("1.000"));
            positive.setReason("positive feedback, no knowledge change");
            return positive;
        }

        // 1. 确定性候选先行
        ChangeTypeResult deterministic = deterministicCandidates(diff);
        if (deterministic != null && deterministic.getConfidence() != null
                && deterministic.getConfidence().compareTo(HIGH_CONFIDENCE) >= 0) {
            return deterministic;
        }

        // 2. LLM 单次结构化输出兜底（可用 ChatModel 时）
        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel != null) {
            ChangeTypeResult llmResult = llmClassify(chatModel, feedbackType, diff,
                    question, userComment, attributionJson, deterministic);
            if (llmResult != null) {
                return llmResult;
            }
        }

        // 3. 确定性低置信候选可降级返回，否则人工
        if (deterministic != null && deterministic.getConfidence() != null
                && deterministic.getConfidence().compareTo(new BigDecimal("0.5")) >= 0) {
            return deterministic;
        }
        return ChangeTypeResult.manual("无法判定修改类型：diff 特征不足且 LLM 兜底不可用");
    }

    /** 归因上下文结构化字段判定（弱类型 JSON 快照，键值截断后使用） */
    private ChangeTypeResult attributionCandidates(String attributionJson) {
        if (attributionJson == null || attributionJson.isBlank()) {
            return null;
        }
        try {
            JSONObject obj = JSON.parseObject(attributionJson);
            if (obj == null) {
                return null;
            }
            String caliber = obj.getString("caliberText") == null
                    ? obj.getString("caliber") : obj.getString("caliberText");
            String rootCause = obj.getString("rootCause");
            String dimension = obj.getString("dimension");
            String metric = obj.getString("metricName");
            if ((caliber != null && !caliber.isBlank())
                    || (metric != null && !metric.isBlank())) {
                return result(ChangeType.ATTRIBUTION_CALIBER.getValue(), "0.9", "归因口径/指标反馈");
            }
            if (rootCause != null && !rootCause.isBlank()) {
                return result(ChangeType.ATTRIBUTION_CAUSE.getValue(), "0.88", "归因根因反馈");
            }
            if (dimension != null && !dimension.isBlank()) {
                return result(ChangeType.ATTRIBUTION_SCOPE.getValue(), "0.88", "归因维度/范围反馈");
            }
        } catch (Exception e) {
            log.debug("feedback: attributionContext 解析失败(忽略): {}", e.getMessage());
        }
        return null;
    }

    private ChangeTypeResult deterministicCandidates(DiffSummary diff) {
        if (diff == null) {
            return null;
        }
        if (diff.isAstParsed()) {
            if (!diff.getAddedWhere().isEmpty()) {
                return result(ChangeType.TIME_RANGE_ERROR.getValue(), "0.9", "where 时间条件变化");
            }
            if (diff.isJoinChanged()) {
                return result(ChangeType.JOIN_ERROR.getValue(), "0.88", "JOIN 数量/类型变化");
            }
            if (diff.isAggregateChanged()) {
                return result(ChangeType.AGG_ERROR.getValue(), "0.88", "聚合/分组变化");
            }
            if (!diff.getChangedTables().isEmpty() && diff.getChangedColumns().isEmpty()) {
                return result(ChangeType.SEMANTIC_ERROR.getValue(), "0.75", "表集合变化");
            }
            if (!diff.getChangedColumns().isEmpty()) {
                return result(ChangeType.FIELD_ERROR.getValue(), "0.8", "查询列变化");
            }
            return result(ChangeType.SEMANTIC_ERROR.getValue(), "0.55", "AST 结构无显著差异但 SQL 不同");
        }
        // 文本 diff 降级
        if (diff.isTextDiffDegraded()) {
            if (diff.isNormalizedChanged()) {
                return result(ChangeType.SEMANTIC_ERROR.getValue(), "0.6", "文本归一化存在差异(降级)");
            }
            return result(ChangeType.OTHER.getValue(), "0.6", "文本归一化无差异");
        }
        return null;
    }

    private ChangeTypeResult llmClassify(ChatModel chatModel, String feedbackType,
                                         DiffSummary diff, String question, String userComment,
                                         String attributionJson,
                                         ChangeTypeResult candidate) {
        String candidateHint = candidate == null
                ? "无" : candidate.getChangeType() + "@" + candidate.getConfidence();
        String diffText = diffTextForPrompt(diff);
        String prompt = """
                你是数据分析 SQL 反馈分类器。根据反馈类型、SQL 结构差异摘要、用户问题/备注，
                判定本次反馈属于哪种修改类型。只能输出 JSON：
                {"change_type": "TIME_RANGE_ERROR", "confidence": 0.85, "reason": "一句话依据"}
                change_type 取值：SQL_ERROR/FIELD_ERROR/SEMANTIC_ERROR/JOIN_ERROR/
                TIME_RANGE_ERROR/AGG_ERROR/ATTRIBUTION_SCOPE/ATTRIBUTION_CAUSE/
                ATTRIBUTION_CALIBER/CONCEPT_SUPPLEMENT/OTHER。
                若反馈类型为 EXPLANATION 且用户主要是补充概念/口径，优先 CONCEPT_SUPPLEMENT。
                反馈类型: %s
                diff 摘要: %s
                原始问题: %s
                用户备注: %s
                归因上下文: %s
                确定性候选: %s
                """.formatted(
                safe(feedbackType), diffText, safe(question), safe(userComment),
                safe(attributionJson), candidateHint);
        try {
            String text = chatModel.call(new Prompt(prompt))
                    .getResult().getOutput().getText();
            JSONObject obj = JSON.parseObject(extractJson(text));
            if (obj == null) {
                log.warn("feedback: LLM 分类输出非法(无法解析 JSON)");
                return null;
            }
            String type = obj.getString("change_type");
            if (!ChangeType.isValid(type)) {
                log.warn("feedback: LLM 分类输出非法 change_type={}", type);
                return null;
            }
            ChangeTypeResult result = new ChangeTypeResult();
            result.setChangeType(type);
            result.setConfidence(obj.getBigDecimal("confidence") == null
                    ? new BigDecimal("0.6") : obj.getBigDecimal("confidence"));
            result.setReason(obj.getString("reason") == null ? "llm classify" : obj.getString("reason"));
            return result;
        } catch (Exception e) {
            log.warn("feedback: LLM 分类调用失败(降级确定性/人工): {}", e.getMessage());
            return null;
        }
    }

    /** 构造 LLM prompt 的 diff 摘要（不含 SQL 原文，截断） */
    private String diffTextForPrompt(DiffSummary diff) {
        if (diff == null) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("ast=").append(diff.isAstParsed()).append("; ");
        if (diff.isAstParsed()) {
            if (!diff.getChangedTables().isEmpty()) {
                sb.append("tables=").append(diff.getChangedTables()).append("; ");
            }
            if (!diff.getChangedColumns().isEmpty()) {
                sb.append("columns=").append(diff.getChangedColumns()).append("; ");
            }
            if (!diff.getAddedWhere().isEmpty()) {
                sb.append("whereTime=").append(diff.getAddedWhere()).append("; ");
            }
            sb.append("joinChanged=").append(diff.isJoinChanged()).append("; ");
            sb.append("aggregateChanged=").append(diff.isAggregateChanged());
        } else {
            sb.append("textDiffDegraded=").append(diff.isNormalizedChanged());
        }
        String text = sb.toString();
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    /** 提取 JSON 片段（兼容 LLM 附言文本） */
    private String extractJson(String text) {
        if (!StringUtils.hasText(text)) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "{}";
    }

    private static String safe(String text) {
        if (text == null || text.isBlank()) {
            return "无";
        }
        String t = text.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static ChangeTypeResult result(String type, String confidence, String reason) {
        ChangeTypeResult r = new ChangeTypeResult();
        r.setChangeType(type);
        r.setConfidence(new BigDecimal(confidence));
        r.setReason(reason);
        return r;
    }
}
