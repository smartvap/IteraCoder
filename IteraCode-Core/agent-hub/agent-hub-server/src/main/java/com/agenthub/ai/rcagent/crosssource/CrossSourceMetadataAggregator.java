package com.agenthub.ai.rcagent.crosssource;

import com.agenthub.ai.rcagent.engine.node.RcaNodeUtils;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataDictionaryLookup;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataDictionaryLookup.DictRow;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataDictionaryLookup.LookupResult;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataEvidenceRenderer;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataQuestionDetector.MetadataCategory;
import com.agenthub.ai.rcagent.kb.dto.KbRelationRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSchemaRetrievalDTO;
import com.agenthub.ai.rcagent.kb.service.KbRetrievalService;
import com.agenthub.ai.rcagent.kb.vo.KbColumnVO;
import com.agenthub.ai.rcagent.kb.vo.KbRelationVO;
import com.agenthub.ai.rcagent.kb.vo.KbSchemaRetrievalVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跨源元数据融合适配器（brownfield 适配层，module-003）。
 *
 * <p>包装既有能力（<b>均零改动</b>）：{@link RcaMetadataDictionaryLookup}（按 {@code ds_id} 维度化直查，
 * 复用其表名解析与渲染器 {@link RcaMetadataEvidenceRenderer}）与 {@link KbRetrievalService}
 * （{@code retrieveSchema} / {@code retrieveRelation} 兜底通道）。</p>
 *
 * <p>契约（api-contract §4.8）：逐源取表/列（含类型）与关系（{@code kb_relation} 为空 ⇒ 空列表，
 * <b>不视为失败</b>，仅标注「关系证据不可用」）；单源失败 → 该源证据标注「元数据不可用」并继续，
 * <b>永不抛异常</b>；输出 truncate 限长（每源 ≤ {@code 2500} 字符）；日志仅记计数。</p>
 */
@Slf4j
@Component
public class CrossSourceMetadataAggregator {

    /** 兜底 schema 检索 topK */
    private static final int SCHEMA_TOP_K = 50;
    /** 关键词长度上限（问题过长时截断，避免 LIKE 参数膨胀） */
    private static final int KEYWORD_MAX_LEN = 100;
    /** 关系渲染段长度上限 */
    private static final int RELATION_TEXT_MAX_LEN = 800;

    private final KbRetrievalService kbRetrievalService;
    private final RcaMetadataDictionaryLookup dictionaryLookup;

    public CrossSourceMetadataAggregator(
            ObjectProvider<KbRetrievalService> kbRetrievalServiceProvider,
            ObjectProvider<RcaMetadataDictionaryLookup> dictionaryLookupProvider) {
        // ObjectProvider 保证容器启动不受可选检索依赖缺失影响（缺服务时证据降级为「元数据不可用」）
        this.kbRetrievalService = kbRetrievalServiceProvider.getIfAvailable();
        this.dictionaryLookup = dictionaryLookupProvider.getIfAvailable();
    }

    /**
     * 逐源聚合元数据证据（永不为 null；顺序与入参 {@code dsIds} 一致）。
     *
     * @param dsIds    候选源集合（主源在首）
     * @param question 用户问题（用于表名解析；空白时仅走关键词/直查降级）
     * @return 逐源证据（{@code sourceIndex} 从 1 开始）
     */
    public List<SourceMetadataEvidence> aggregate(List<String> dsIds, String question) {
        List<SourceMetadataEvidence> evidences = new ArrayList<>();
        if (dsIds == null || dsIds.isEmpty()) {
            return evidences;
        }
        int availableCount = 0;
        for (int i = 0; i < dsIds.size(); i++) {
            SourceMetadataEvidence evidence = aggregateOne(i + 1, dsIds.get(i), question);
            if (evidence.available()) {
                availableCount++;
            }
            evidences.add(evidence);
        }
        log.info("RCAgent 跨源元数据融合: sourceCount={}, availableCount={}", dsIds.size(), availableCount);
        return evidences;
    }

    /** 单源证据聚合（任何异常均降级为「元数据不可用」，不阻断其它源） */
    private SourceMetadataEvidence aggregateOne(int sourceIndex, String dsId, String question) {
        if (dsId == null || dsId.isBlank()) {
            return unavailable(sourceIndex, dsId);
        }
        try {
            Map<String, String> columnTypes = new LinkedHashMap<>();
            String schemaText = resolveSchemaText(dsId, question, columnTypes);
            String relationText = resolveRelationText(dsId, question);
            StringBuilder rendered = new StringBuilder();
            if (!schemaText.isBlank()) {
                rendered.append(schemaText);
            }
            if (!relationText.isBlank()) {
                if (rendered.length() > 0) {
                    rendered.append('\n');
                }
                rendered.append(relationText);
            } else if (rendered.length() > 0) {
                rendered.append('\n').append(CrossSourceConstants.EVIDENCE_RELATION_UNAVAILABLE);
            }
            if (rendered.length() == 0) {
                return unavailable(sourceIndex, dsId);
            }
            String text = RcaNodeUtils.truncate(rendered.toString(), CrossSourceConstants.EVIDENCE_MAX_LEN);
            log.info("RCAgent 跨源元数据证据: sourceIndex={}, columnCount={}, relationAvailable={}",
                    sourceIndex, columnTypes.size(), !relationText.isBlank());
            return new SourceMetadataEvidence(sourceIndex, dsId,
                    Collections.unmodifiableMap(new LinkedHashMap<>(columnTypes)), text, true);
        } catch (Exception e) {
            // 单源失败不阻断：仅标注该源证据不可用（异常摘要截断，禁止拼接连接信息）
            log.warn("RCAgent 跨源元数据证据获取失败（降级）: sourceIndex={}, error={}",
                    sourceIndex, RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            return unavailable(sourceIndex, dsId);
        }
    }

    /** 优先字典直查（按 ds_id 隔离、复用既有表名解析），不可用时回退 schema 检索 */
    private String resolveSchemaText(String dsId, String question, Map<String, String> columnTypes) {
        String dictText = lookupColumnsByDictionary(dsId, question, columnTypes);
        if (!dictText.isBlank()) {
            return dictText;
        }
        return retrieveSchemaText(dsId, question, columnTypes);
    }

    /** 经既有字典直查取表/列（含类型）证据；失败或未定位到表返回空串 */
    private String lookupColumnsByDictionary(String dsId, String question, Map<String, String> columnTypes) {
        if (dictionaryLookup == null || question == null || question.isBlank()) {
            return "";
        }
        LookupResult result = dictionaryLookup.lookup(dsId, question, MetadataCategory.COLUMN_DEF);
        if (result == null || result.resolvedTables() == null || result.resolvedTables().isEmpty()) {
            return "";
        }
        collectColumnTypes(result, columnTypes);
        return RcaMetadataEvidenceRenderer.render(result);
    }

    /** 从直查结果行收集「列名（小写）→ 类型」映射，供 key 存在性与类型族校验 */
    private void collectColumnTypes(LookupResult result, Map<String, String> columnTypes) {
        if (result.rows() == null) {
            return;
        }
        for (DictRow row : result.rows()) {
            if (row == null || row.column() == null) {
                continue;
            }
            KbColumnVO column = row.column();
            if (column.getColumnName() == null || column.getColumnName().isBlank()) {
                continue;
            }
            columnTypes.putIfAbsent(column.getColumnName().trim().toLowerCase(Locale.ROOT), column.getDataType());
        }
    }

    /** 回退通道：既有 schema 检索（关键词=问题截断），扁平化为证据行文本 */
    private String retrieveSchemaText(String dsId, String question, Map<String, String> columnTypes) {
        if (kbRetrievalService == null) {
            return "";
        }
        KbSchemaRetrievalDTO dto = new KbSchemaRetrievalDTO();
        dto.setDsId(dsId);
        dto.setKeyword(question == null || question.isBlank()
                ? null : RcaNodeUtils.truncate(question.trim(), KEYWORD_MAX_LEN));
        dto.setTopK(SCHEMA_TOP_K);
        KbSchemaRetrievalVO schema = kbRetrievalService.retrieveSchema(dto);
        if (schema == null || schema.getColumns() == null || schema.getColumns().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[元数据证据] 列清单：");
        int shown = 0;
        for (KbColumnVO column : schema.getColumns()) {
            if (column == null || column.getColumnName() == null || column.getColumnName().isBlank()) {
                continue;
            }
            sb.append('\n').append("- [").append(column.getTableName() == null ? "" : column.getTableName())
                    .append("] ").append(column.getColumnName());
            if (column.getDataType() != null && !column.getDataType().isBlank()) {
                sb.append(" 类型:").append(column.getDataType());
            }
            columnTypes.putIfAbsent(column.getColumnName().trim().toLowerCase(Locale.ROOT), column.getDataType());
            shown++;
            if (shown >= 200) {
                break;
            }
        }
        return shown == 0 ? "" : sb.toString();
    }

    /** 关系证据（{@code kb_relation} 为空 ⇒ 空串，不视为失败） */
    private String resolveRelationText(String dsId, String question) {
        if (kbRetrievalService == null || question == null || question.isBlank()) {
            return "";
        }
        try {
            KbRelationRetrievalDTO dto = new KbRelationRetrievalDTO();
            dto.setDsId(dsId);
            dto.setTableA(RcaNodeUtils.truncate(question.trim(), KEYWORD_MAX_LEN));
            dto.setTopK(10);
            var vo = kbRetrievalService.retrieveRelation(dto);
            if (vo == null || vo.getRelations() == null || vo.getRelations().isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("[元数据证据] 表关系：");
            int shown = 0;
            for (KbRelationVO relation : vo.getRelations()) {
                if (relation == null || relation.getTableA() == null || relation.getColumnA() == null
                        || relation.getTableB() == null || relation.getColumnB() == null) {
                    continue;
                }
                sb.append('\n').append("- 关系:").append(relation.getTableA()).append('.')
                        .append(relation.getColumnA()).append('=').append(relation.getTableB()).append('.')
                        .append(relation.getColumnB());
                shown++;
                if (shown >= 20) {
                    break;
                }
            }
            return shown == 0 ? "" : RcaNodeUtils.truncate(sb.toString(), RELATION_TEXT_MAX_LEN);
        } catch (Exception e) {
            // 关系通道失败不阻断（kb_relation 当前为空属正常情况）
            return "";
        }
    }

    private static SourceMetadataEvidence unavailable(int sourceIndex, String dsId) {
        return new SourceMetadataEvidence(sourceIndex, dsId, Map.of(),
                CrossSourceConstants.EVIDENCE_UNAVAILABLE, false);
    }

    /**
     * 单源元数据证据（不可变读模型，**不入 State**，仅用于规划提示词与计划校验）。
     *
     * @param sourceIndex  源序号（对外唯一标识）
     * @param dsId         源 dsId（内部）
     * @param columnTypes  列名（小写）→ 类型（供 join key 存在性与类型族校验；证据不可用时为空映射）
     * @param renderedText 渲染后的证据文本（每源 ≤ 2500 字符；不可用时为「元数据不可用」）
     * @param available    该源元数据是否可用
     */
    public record SourceMetadataEvidence(int sourceIndex, String dsId, Map<String, String> columnTypes,
                                         String renderedText, boolean available) {

        /** 是否包含指定列（大小写不敏感；空白列名返回 false） */
        public boolean hasColumn(String columnName) {
            if (columnName == null || columnName.isBlank()) {
                return false;
            }
            return columnTypes.containsKey(columnName.trim().toLowerCase(Locale.ROOT));
        }

        /** 指定列的类型（未收录返回 null） */
        public String columnType(String columnName) {
            if (columnName == null || columnName.isBlank()) {
                return null;
            }
            return columnTypes.get(columnName.trim().toLowerCase(Locale.ROOT));
        }
    }
}
