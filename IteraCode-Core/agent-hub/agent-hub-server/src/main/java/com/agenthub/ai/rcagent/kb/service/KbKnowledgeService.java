package com.agenthub.ai.rcagent.kb.service;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.dbaccess.meta.ColumnTypeSignature;
import com.agenthub.ai.rcagent.kb.common.KbJsonUtils;
import com.agenthub.ai.rcagent.kb.constant.KbDbType;
import com.agenthub.ai.rcagent.kb.constant.KbErrorCode;
import com.agenthub.ai.rcagent.kb.constant.KbKnowledgeSource;
import com.agenthub.ai.rcagent.kb.constant.KbKnowledgeStatus;
import com.agenthub.ai.rcagent.kb.constant.KbReviewTaskType;
import com.agenthub.ai.rcagent.kb.constant.KbTargetObjectType;
import com.agenthub.ai.rcagent.kb.dto.KbBizLinkSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbColumnItemDTO;
import com.agenthub.ai.rcagent.kb.dto.KbColumnSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbConceptSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbDeprecateDTO;
import com.agenthub.ai.rcagent.kb.dto.KbDetailDTO;
import com.agenthub.ai.rcagent.kb.dto.KbIndexItemDTO;
import com.agenthub.ai.rcagent.kb.dto.KbMetricSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbRelationSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSchemaBatchDTO;
import com.agenthub.ai.rcagent.kb.dto.KbTableSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbTemplateSaveDTO;
import com.agenthub.ai.rcagent.kb.entity.KbBizLink;
import com.agenthub.ai.rcagent.kb.entity.KbBusinessConcept;
import com.agenthub.ai.rcagent.kb.entity.KbColumn;
import com.agenthub.ai.rcagent.kb.entity.KbIndex;
import com.agenthub.ai.rcagent.kb.entity.KbKnowledgeRow;
import com.agenthub.ai.rcagent.kb.entity.KbMetric;
import com.agenthub.ai.rcagent.kb.entity.KbRelation;
import com.agenthub.ai.rcagent.kb.entity.KbSqlTemplate;
import com.agenthub.ai.rcagent.kb.entity.KbTable;
import com.agenthub.ai.rcagent.kb.mapper.KbBizLinkMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbBusinessConceptMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbColumnMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbIndexMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbMetricMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbRelationMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbSqlTemplateMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbTableMapper;
import com.agenthub.ai.rcagent.kb.support.KbKnowledgeRowRouter;
import com.agenthub.ai.rcagent.kb.vo.KbChangeSummaryVO;
import com.agenthub.ai.rcagent.kb.vo.KbDetailVO;
import com.agenthub.ai.rcagent.kb.vo.KbImpactItemVO;
import com.agenthub.ai.rcagent.kb.vo.KbSaveResultVO;
import com.agenthub.ai.rcagent.kb.vo.KbSchemaBatchVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识对象存储（接口1-10）：kb_table/kb_column/... upsert、Schema 批量同步、
 * 软删除与详情查询。
 *
 * <p>公共语义（BR-002/BR-004）：
 * <ul>
 *   <li>可信度默认按来源：SCHEMA=1.0/CODE=0.95/RUNTIME=0.8/LLM=0.6(强制人工)/HUMAN=1.0；</li>
 *   <li>LLM 推断知识强制 draft+verified=0 并自动创建审核任务；</li>
 *   <li>SCHEMA/CODE 自动事实默认 active+verified=1（调用方可显式传 draft/0 覆盖）；</li>
 *   <li>结构变更（列新增/类型/主外键变化）联动引用模板/链路 needs_review。</li>
 * </ul>
 * 证据列 evidence 可能含 SQL/业务文本：仅存库/展示，日志只打摘要不落全文。</p>
 */
@Slf4j
@Service
public class KbKnowledgeService {

    /** kb_index.index_columns 列长度上限（VARCHAR(1024)），超限由应用侧截断 */
    private static final int INDEX_COLUMNS_MAX_LEN = 1024;

    private final KbTableMapper tableMapper;
    private final KbColumnMapper columnMapper;
    private final KbIndexMapper indexMapper;
    private final KbBusinessConceptMapper conceptMapper;
    private final KbMetricMapper metricMapper;
    private final KbSqlTemplateMapper sqlTemplateMapper;
    private final KbBizLinkMapper bizLinkMapper;
    private final KbRelationMapper relationMapper;
    private final KbKnowledgeRowRouter rowRouter;
    private final KbReviewService reviewService;
    private final KbConfidenceService confidenceService;

    public KbKnowledgeService(KbTableMapper tableMapper,
                              KbColumnMapper columnMapper,
                              KbIndexMapper indexMapper,
                              KbBusinessConceptMapper conceptMapper,
                              KbMetricMapper metricMapper,
                              KbSqlTemplateMapper sqlTemplateMapper,
                              KbBizLinkMapper bizLinkMapper,
                              KbRelationMapper relationMapper,
                              KbKnowledgeRowRouter rowRouter,
                              KbReviewService reviewService,
                              KbConfidenceService confidenceService) {
        this.tableMapper = tableMapper;
        this.columnMapper = columnMapper;
        this.indexMapper = indexMapper;
        this.conceptMapper = conceptMapper;
        this.metricMapper = metricMapper;
        this.sqlTemplateMapper = sqlTemplateMapper;
        this.bizLinkMapper = bizLinkMapper;
        this.relationMapper = relationMapper;
        this.rowRouter = rowRouter;
        this.reviewService = reviewService;
        this.confidenceService = confidenceService;
    }

    // ==================== 接口1 保存表知识 ====================

    public KbSaveResultVO saveTable(KbTableSaveDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(dto.getTableName() == null || dto.getTableName().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "table_name 不能为空"));
        validateSourceStatus(dto.getKnowledgeSource(), dto.getStatus(), dto.getConfidence());

        String source = dto.getKnowledgeSource() == null ? KbKnowledgeSource.SCHEMA.getValue() : dto.getKnowledgeSource();
        boolean llm = KbKnowledgeSource.LLM.getValue().equals(source);

        KbTable existing = findTableByUnique(dto.getDsId(), dto.getSchemaName(), dto.getTableName());
        KbTable row = new KbTable();
        boolean isUpdate = existing != null;
        boolean wasDeprecated = existing != null && KbKnowledgeStatus.DEPRECATED.getValue().equals(existing.getStatus());

        Date now = new Date();
        row.setDsId(dto.getDsId());
        row.setDbType(blankToDefault(dto.getDbType(), "MYSQL"));
        row.setSchemaName(dto.getSchemaName());
        row.setTableName(dto.getTableName());
        row.setTableType(blankToDefault(dto.getTableType(), "TABLE"));
        row.setCommentText(dto.getCommentText());
        row.setSemanticTags(KbJsonUtils.toJson(dto.getSemanticTags()));
        row.setSampleStats(KbJsonUtils.toJson(dto.getSampleStats()));
        row.setKnowledgeSource(source);
        row.setConfidence(dto.getConfidence() == null ? KbKnowledgeSource.defaultConfidenceOf(source) : dto.getConfidence());
        row.setEvidence(dto.getEvidence());

        // LLM 强制 draft，且不入高置信检索；否则按默认语义解析
        if (llm) {
            row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
            row.setVerified(0);
        } else {
            row.setStatus(dto.getStatus() == null
                    ? defaultAutoStatus(source) : dto.getStatus());
            row.setVerified(dto.getVerified() == null
                    ? defaultVerified(source, row.getStatus()) : dto.getVerified());
        }

        if (!isUpdate) {
            row.setVersion(1);
            row.setHitCount(0);
            row.setErrorCount(0);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            tableMapper.insert(row);
        } else {
            // deprecated 恢复：非 LLM 新版本重新转正；LLM 仍保持 draft 待人工（BR-002）
            if (wasDeprecated) {
                if (llm) {
                    row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
                    row.setVerified(0);
                } else {
                    row.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
                    row.setVerified(dto.getVerified() == null
                            ? defaultVerified(source, KbKnowledgeStatus.ACTIVE.getValue()) : dto.getVerified());
                }
                row.setVersion(existing.getVersion() + 1);
            } else {
                // 未显式传状态/校验/置信度时保留旧值（避免 upsert 把 active 打回 draft）
                if (llm) {
                    // LLM 来源强制 draft/未验证，避免旧行状态被保留导致 LLM 直转正
                    row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
                    row.setVerified(0);
                } else {
                    if (dto.getStatus() == null) {
                        row.setStatus(existing.getStatus());
                    }
                    if (dto.getVerified() == null) {
                        row.setVerified(existing.getVerified());
                    }
                    if (dto.getConfidence() == null) {
                        row.setConfidence(existing.getConfidence());
                    }
                }
                row.setVersion(existing.getVersion() + 1);
            }
            row.setHitCount(existing.getHitCount());
            row.setErrorCount(existing.getErrorCount());
            row.setEvidence(appendDiffEvidence(existing.getEvidence(), dto.getEvidence(),
                    "v" + existing.getVersion() + "->" + row.getVersion(), row.getCommentText()));
            row.setCreateTime(existing.getCreateTime());
            row.setUpdateTime(now);
            row.setId(existing.getId());
            tableMapper.updateById(row);
        }

        // 级联列保存 + 结构变更影响
        List<KbImpactItemVO> impacted = new ArrayList<>();
        boolean structureChanged = false;
        if (dto.getColumns() != null && !dto.getColumns().isEmpty()) {
            if (isUpdate) {
                structureChanged = cascadeColumns(row, dto.getColumns(), impacted);
            } else {
                // 新建表：直接逐列插入，不算“结构变更”（无既有模板引用需复核）
                for (KbColumnItemDTO c : dto.getColumns()) {
                    insertColumn(row, c);
                }
            }
        }

        // 级联索引保存（可选能力）：仅当调用方显式传入 indexes 时才触碰 kb_index；
        // 未传（null）时完全不查询/不写库，既有调用方零影响（spec A-2.8）。
        // 索引级变更摘要由 schemaBatch 的索引级 diff 统一产出，此处 summary 仅供 cascadeIndexes 内部记录。
        if (dto.getIndexes() != null) {
            cascadeIndexes(row, dto.getIndexes(), new KbChangeSummaryVO());
        }

        KbSaveResultVO vo = buildSaveVO(row);
        if (llm) {
            // BR-002 LLM 强制人工：自动创建审核任务
            vo.setReviewTaskId(reviewService.createTaskInternal(row.getDsId(),
                    KbReviewTaskType.LLM_INFERENCE.getValue(),
                    KbTargetObjectType.TABLE.getValue(),
                    targetJson(row.getId(), row.getTableName()), null,
                    "llm table save auto-review"));
        }
        if (structureChanged) {
            vo.setStructureChanged(true);
            vo.setImpacted(impacted.isEmpty() ? null : impacted);
        }
        return vo;
    }

    // ==================== 接口2 保存字段知识 ====================

    public KbSaveResultVO saveColumn(KbColumnSaveDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(dto.getColumnName() == null || dto.getColumnName().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "column_name 不能为空"));
        validateSourceStatus(dto.getKnowledgeSource(), dto.getStatus(), dto.getConfidence());

        // 定位父表：table_id 优先，否则 schema_name+table_name
        KbTable parent = null;
        if (dto.getTableId() != null) {
            parent = tableMapper.selectById(dto.getTableId());
        } else {
            ThrowUtils.throwIf(dto.getTableName() == null || dto.getTableName().isBlank(),
                    new BusinessException(ErrorCode.PARAMS_ERROR, "table_id 与 table_name 至少提供一个"));
            parent = findTableByUnique(dto.getDsId(), dto.getSchemaName(), dto.getTableName());
        }
        ThrowUtils.throwIf(parent == null,
                new BusinessException(ErrorCode.NOT_FOUND_ERROR, "父表不存在"));
        ThrowUtils.throwIf(!dto.getDsId().equals(parent.getDsId()),
                new BusinessException(KbErrorCode.DS_ID_MISSING_OR_CONFLICT, "ds_id 隔离冲突：父表属于 " + parent.getDsId()));

        String source = dto.getKnowledgeSource() == null ? KbKnowledgeSource.SCHEMA.getValue() : dto.getKnowledgeSource();
        boolean llm = KbKnowledgeSource.LLM.getValue().equals(source);

        KbColumn existing = columnMapper.selectOne(new QueryWrapper<KbColumn>()
                .eq("ds_id", dto.getDsId())
                .eq("table_id", parent.getId())
                .eq("column_name", dto.getColumnName())
                .last("LIMIT 1"));

        boolean structureChanged = false;
        Date now = new Date();
        KbColumn row = new KbColumn();
        row.setDsId(dto.getDsId());
        row.setTableId(parent.getId());
        row.setDbType(parent.getDbType());
        row.setSchemaName(parent.getSchemaName());
        row.setTableName(parent.getTableName());
        row.setColumnName(dto.getColumnName());
        row.setDataType(dto.getDataType());
        row.setIsPk(dto.getIsPk() == null ? 0 : dto.getIsPk());
        row.setIsFk(dto.getIsFk() == null ? 0 : dto.getIsFk());
        row.setCommentText(dto.getCommentText());
        row.setSemanticTags(KbJsonUtils.toJson(dto.getSemanticTags()));
        row.setSampleStats(KbJsonUtils.toJson(dto.getSampleStats()));
        row.setKnowledgeSource(source);
        row.setConfidence(dto.getConfidence() == null ? KbKnowledgeSource.defaultConfidenceOf(source) : dto.getConfidence());
        row.setEvidence(dto.getEvidence());

        if (llm) {
            row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
            row.setVerified(0);
        } else {
            row.setStatus(dto.getStatus() == null ? defaultAutoStatus(source) : dto.getStatus());
            row.setVerified(dto.getVerified() == null ? defaultVerified(source, row.getStatus()) : dto.getVerified());
        }

        if (existing == null) {
            // 新列：结构变更（SELECT * 语义变化）
            structureChanged = true;
            row.setVersion(1);
            row.setHitCount(0);
            row.setErrorCount(0);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            columnMapper.insert(row);
        } else {
            // 更新：未显式传状态/校验/置信度时保留旧值
            if (dto.getStatus() == null) {
                row.setStatus(existing.getStatus());
            }
            if (dto.getVerified() == null) {
                row.setVerified(existing.getVerified());
            }
            if (dto.getConfidence() == null) {
                row.setConfidence(existing.getConfidence());
            }
            boolean typeChanged = !eqStr(existing.getDataType(), row.getDataType())
                    || !eqInt(existing.getIsPk(), row.getIsPk())
                    || !eqInt(existing.getIsFk(), row.getIsFk());
            if (typeChanged) {
                structureChanged = true;
            }
            row.setVersion(existing.getVersion() + 1);
            row.setHitCount(existing.getHitCount());
            row.setErrorCount(existing.getErrorCount());
            row.setEvidence(appendDiffEvidence(existing.getEvidence(), dto.getEvidence(),
                    "v" + existing.getVersion() + "->" + row.getVersion(), row.getCommentText()));
            row.setCreateTime(existing.getCreateTime());
            row.setUpdateTime(now);
            row.setId(existing.getId());
            columnMapper.updateById(row);
        }

        // 字段结构变化联动父表引用模板
        List<KbImpactItemVO> impacted = new ArrayList<>();
        if (structureChanged) {
            impacted = impactByTable(parent.getDsId(), parent.getTableName());
        }
        KbSaveResultVO vo = buildSaveVO(row);
        if (llm) {
            // BR-002 LLM 强制人工：自动创建审核任务
            vo.setReviewTaskId(reviewService.createTaskInternal(row.getDsId(),
                    KbReviewTaskType.LLM_INFERENCE.getValue(),
                    KbTargetObjectType.COLUMN.getValue(),
                    targetJson(row.getId(), row.getColumnName()), null,
                    "llm column save auto-review"));
        }
        if (structureChanged) {
            vo.setStructureChanged(true);
            vo.setImpacted(impacted.isEmpty() ? null : impacted);
        }
        return vo;
    }

    // ==================== 接口3 保存表关系 ====================

    public KbSaveResultVO saveRelation(KbRelationSaveDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(dto.getTableA() == null || dto.getTableA().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "table_a 不能为空"));
        ThrowUtils.throwIf(dto.getTableB() == null || dto.getTableB().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "table_b 不能为空"));
        ThrowUtils.throwIf(dto.getRelationType() == null || dto.getRelationType().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "relation_type 不能为空"));
        validateSourceStatus(dto.getKnowledgeSource(), dto.getStatus(), dto.getConfidence());

        String relationSource = blankToDefault(dto.getRelationSource(), "RUNTIME_JOIN");
        String source = dto.getKnowledgeSource() == null
                ? ("FK".equals(relationSource) || "CODE".equals(relationSource)
                    ? KbKnowledgeSource.CODE.getValue() : KbKnowledgeSource.RUNTIME.getValue())
                : dto.getKnowledgeSource();
        boolean llm = KbKnowledgeSource.LLM.getValue().equals(source);

        KbRelation existing = relationMapper.selectOne(new QueryWrapper<KbRelation>()
                .eq("ds_id", dto.getDsId())
                .eq("table_a", dto.getTableA())
                .eq("table_b", dto.getTableB())
                .eq("relation_type", dto.getRelationType())
                .last("LIMIT 1"));

        Date now = new Date();
        KbRelation row = new KbRelation();
        row.setDsId(dto.getDsId());
        row.setDbType(blankToDefault(dto.getDbType(), "MYSQL"));
        row.setTableA(dto.getTableA());
        row.setColumnA(dto.getColumnA());
        row.setTableB(dto.getTableB());
        row.setColumnB(dto.getColumnB());
        row.setJoinFields(KbJsonUtils.toJson(dto.getJoinFields()));
        row.setRelationType(dto.getRelationType());
        row.setRelationSource(relationSource);
        row.setKnowledgeSource(source);
        row.setConfidence(dto.getConfidence() == null ? KbKnowledgeSource.defaultConfidenceOf(source) : dto.getConfidence());
        row.setEvidence(dto.getEvidence());
        if (llm) {
            row.setVerified(0);
            row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
        } else {
            row.setVerified(dto.getVerified() == null ? 0 : dto.getVerified());
            row.setStatus(blankToDefault(dto.getStatus(), KbKnowledgeStatus.DRAFT.getValue()));
        }

        if (existing == null) {
            row.setVersion(1);
            row.setHitCount(0);
            row.setErrorCount(0);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            relationMapper.insert(row);
        } else {
            if (!llm) {
                // 未显式传状态/校验/置信度时保留旧值
                if (dto.getStatus() == null) {
                    row.setStatus(existing.getStatus());
                }
                if (dto.getVerified() == null) {
                    row.setVerified(existing.getVerified());
                }
                if (dto.getConfidence() == null) {
                    row.setConfidence(existing.getConfidence());
                }
            } else {
                row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
                row.setVerified(0);
            }
            row.setVersion(existing.getVersion() + 1);
            row.setHitCount(existing.getHitCount());
            row.setErrorCount(existing.getErrorCount());
            row.setEvidence(appendDiffEvidence(existing.getEvidence(), dto.getEvidence(),
                    "v" + existing.getVersion() + "->" + row.getVersion(), null));
            row.setCreateTime(existing.getCreateTime());
            row.setUpdateTime(now);
            row.setId(existing.getId());
            relationMapper.updateById(row);
        }
        KbSaveResultVO vo = buildSaveVO(row);
        if (llm) {
            vo.setReviewTaskId(reviewService.createTaskInternal(row.getDsId(),
                    KbReviewTaskType.RELATION_SUGGESTION.getValue(),
                    KbTargetObjectType.RELATION.getValue(),
                    targetJson(row.getId(), row.getTableA() + "-" + row.getTableB()), null,
                    "llm relation save auto-review"));
        }
        return vo;
    }

    // ==================== 接口4 保存业务概念 ====================

    public KbSaveResultVO saveConcept(KbConceptSaveDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(dto.getConceptName() == null || dto.getConceptName().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "concept_name 不能为空"));
        ThrowUtils.throwIf(dto.getDefinition() == null || dto.getDefinition().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "definition 不能为空"));

        String source = dto.getKnowledgeSource() == null ? KbKnowledgeSource.LLM.getValue() : dto.getKnowledgeSource();
        boolean llm = KbKnowledgeSource.LLM.getValue().equals(source);

        KbBusinessConcept existing = conceptMapper.selectOne(new QueryWrapper<KbBusinessConcept>()
                .eq("ds_id", dto.getDsId())
                .eq("concept_name", dto.getConceptName())
                .last("LIMIT 1"));

        Date now = new Date();
        KbBusinessConcept row = new KbBusinessConcept();
        row.setDsId(dto.getDsId());
        row.setConceptName(dto.getConceptName());
        row.setDefinition(dto.getDefinition());
        row.setSynonyms(KbJsonUtils.toJson(dto.getSynonyms()));
        row.setRelatedTables(KbJsonUtils.toJson(dto.getRelatedTables()));
        row.setRelatedColumns(KbJsonUtils.toJson(dto.getRelatedColumns()));
        row.setRelatedSqlIds(KbJsonUtils.toJson(dto.getRelatedSqlIds()));
        row.setSpaceName(dto.getSpaceName());
        row.setKnowledgeSource(source);
        row.setConfidence(dto.getConfidence() == null ? KbKnowledgeSource.defaultConfidenceOf(source) : dto.getConfidence());
        row.setEvidence(dto.getEvidence());

        if (llm) {
            row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
            row.setVerified(0);
        } else {
            row.setStatus(blankToDefault(dto.getStatus(), KbKnowledgeStatus.DRAFT.getValue()));
            row.setVerified(dto.getVerified() == null ? 0 : dto.getVerified());
        }

        if (existing == null) {
            row.setVersion(1);
            row.setHitCount(0);
            row.setErrorCount(0);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            conceptMapper.insert(row);
        } else {
            row.setVersion(existing.getVersion() + 1);
            row.setHitCount(existing.getHitCount());
            row.setErrorCount(existing.getErrorCount());
            row.setEvidence(appendDiffEvidence(existing.getEvidence(), dto.getEvidence(),
                    "v" + existing.getVersion() + "->" + row.getVersion(), null));
            row.setCreateTime(existing.getCreateTime());
            row.setUpdateTime(now);
            row.setId(existing.getId());
            conceptMapper.updateById(row);
        }

        KbSaveResultVO vo = buildSaveVO(row);
        if (llm) {
            // BR-002 LLM 强制人工：自动创建审核任务
            vo.setReviewTaskId(reviewService.createTaskInternal(row.getDsId(),
                    KbReviewTaskType.CONCEPT_DEFINITION.getValue(),
                    KbTargetObjectType.BUSINESS_CONCEPT.getValue(),
                    targetJson(row.getId(), row.getConceptName()), null,
                    "llm concept save auto-review"));
        }
        return vo;
    }

    // ==================== 接口5 保存指标定义 ====================

    public KbSaveResultVO saveMetric(KbMetricSaveDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(dto.getMetricName() == null || dto.getMetricName().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "metric_name 不能为空"));
        ThrowUtils.throwIf(dto.getDefinition() == null || dto.getDefinition().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "definition 不能为空"));

        String source = dto.getKnowledgeSource() == null ? KbKnowledgeSource.LLM.getValue() : dto.getKnowledgeSource();
        boolean llm = KbKnowledgeSource.LLM.getValue().equals(source);

        KbMetric existing = metricMapper.selectOne(new QueryWrapper<KbMetric>()
                .eq("ds_id", dto.getDsId())
                .eq("metric_name", dto.getMetricName())
                .last("LIMIT 1"));

        Date now = new Date();
        KbMetric row = new KbMetric();
        row.setDsId(dto.getDsId());
        row.setMetricName(dto.getMetricName());
        row.setDefinition(dto.getDefinition());
        row.setCalculationRule(dto.getCalculationRule());
        row.setRelatedTables(KbJsonUtils.toJson(dto.getRelatedTables()));
        row.setRelatedColumns(KbJsonUtils.toJson(dto.getRelatedColumns()));
        row.setSpaceName(dto.getSpaceName());
        row.setKnowledgeSource(source);
        row.setConfidence(dto.getConfidence() == null ? KbKnowledgeSource.defaultConfidenceOf(source) : dto.getConfidence());
        row.setEvidence(dto.getEvidence());

        if (llm) {
            row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
            row.setVerified(0);
        } else {
            row.setStatus(blankToDefault(dto.getStatus(), KbKnowledgeStatus.DRAFT.getValue()));
            row.setVerified(dto.getVerified() == null ? 0 : dto.getVerified());
        }

        if (existing == null) {
            row.setVersion(1);
            row.setHitCount(0);
            row.setErrorCount(0);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            metricMapper.insert(row);
        } else {
            row.setVersion(existing.getVersion() + 1);
            row.setHitCount(existing.getHitCount());
            row.setErrorCount(existing.getErrorCount());
            row.setEvidence(appendDiffEvidence(existing.getEvidence(), dto.getEvidence(),
                    "v" + existing.getVersion() + "->" + row.getVersion(), null));
            row.setCreateTime(existing.getCreateTime());
            row.setUpdateTime(now);
            row.setId(existing.getId());
            metricMapper.updateById(row);
        }

        KbSaveResultVO vo = buildSaveVO(row);
        if (llm) {
            vo.setReviewTaskId(reviewService.createTaskInternal(row.getDsId(),
                    KbReviewTaskType.METRIC_DEFINITION.getValue(),
                    KbTargetObjectType.METRIC.getValue(),
                    targetJson(row.getId(), row.getMetricName()), null,
                    "llm metric save auto-review"));
        }
        return vo;
    }

    // ==================== 接口6 保存 SQL 模板 ====================

    public KbSaveResultVO saveTemplate(KbTemplateSaveDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(dto.getNormalizedSql() == null || dto.getNormalizedSql().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "normalized_sql 不能为空"));
        validateSourceStatus(dto.getKnowledgeSource(), dto.getStatus(), dto.getConfidence());

        String fingerprint = sha256(dto.getNormalizedSql());
        String source = dto.getKnowledgeSource() == null ? KbKnowledgeSource.RUNTIME.getValue() : dto.getKnowledgeSource();
        boolean llm = KbKnowledgeSource.LLM.getValue().equals(source);

        KbSqlTemplate existing = sqlTemplateMapper.selectOne(new QueryWrapper<KbSqlTemplate>()
                .eq("ds_id", dto.getDsId())
                .eq("sql_fingerprint", fingerprint)
                .last("LIMIT 1"));

        Date now = new Date();
        KbSqlTemplate row = new KbSqlTemplate();
        row.setDsId(dto.getDsId());
        row.setDbType(blankToDefault(dto.getDbType(), "MYSQL"));
        row.setBizAction(dto.getBizAction());
        row.setNormalizedSql(dto.getNormalizedSql());
        row.setSqlFingerprint(fingerprint);
        row.setInvolvedTables(KbJsonUtils.toJson(dto.getInvolvedTables()));
        row.setSampleQuestions(KbJsonUtils.toJson(dto.getSampleQuestions()));
        row.setFreq(dto.getFreq() == null ? 1 : dto.getFreq());
        row.setAccuracy(dto.getAccuracy());
        row.setKnowledgeSource(source);
        row.setConfidence(dto.getConfidence() == null ? KbKnowledgeSource.defaultConfidenceOf(source) : dto.getConfidence());
        row.setEvidence(dto.getEvidence());

        if (llm) {
            row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
            row.setVerified(0);
        } else {
            row.setStatus(blankToDefault(dto.getStatus(), KbKnowledgeStatus.DRAFT.getValue()));
            row.setVerified(dto.getVerified() == null ? 0 : dto.getVerified());
        }

        if (existing == null) {
            row.setVersion(1);
            row.setHitCount(0);
            row.setErrorCount(0);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            sqlTemplateMapper.insert(row);
        } else {
            // 同指纹重复上报：freq 累加 + 问题问法补充；未显式传状态/校验/置信度时保留旧值
            row.setFreq(existing.getFreq() + (dto.getFreq() == null ? 1 : dto.getFreq()));
            if (llm) {
                row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
                row.setVerified(0);
            } else {
                if (dto.getStatus() == null) {
                    row.setStatus(existing.getStatus());
                }
                if (dto.getVerified() == null) {
                    row.setVerified(existing.getVerified());
                }
                if (dto.getConfidence() == null) {
                    row.setConfidence(existing.getConfidence());
                }
            }
            row.setVersion(existing.getVersion() + 1);
            row.setHitCount(existing.getHitCount());
            row.setErrorCount(existing.getErrorCount());
            row.setEvidence(appendDiffEvidence(existing.getEvidence(), dto.getEvidence(),
                    "v" + existing.getVersion() + "->" + row.getVersion(), null));
            row.setCreateTime(existing.getCreateTime());
            row.setUpdateTime(now);
            row.setId(existing.getId());
            sqlTemplateMapper.updateById(row);
        }

        KbSaveResultVO vo = buildSaveVO(row);
        vo.setFingerprint(fingerprint);
        vo.setFreq(row.getFreq());
        if (llm) {
            vo.setReviewTaskId(reviewService.createTaskInternal(row.getDsId(),
                    KbReviewTaskType.TEMPLATE_PROMOTE.getValue(),
                    KbTargetObjectType.SQL_TEMPLATE.getValue(),
                    targetJson(row.getId(), row.getBizAction()), null,
                    "llm template save auto-review"));
        }
        return vo;
    }

    // ==================== 接口7 保存业务链路 ====================

    public KbSaveResultVO saveBizLink(KbBizLinkSaveDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(dto.getBizOperation() == null || dto.getBizOperation().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "biz_operation 不能为空"));
        validateSourceStatus(dto.getKnowledgeSource(), dto.getStatus(), dto.getConfidence());
        if (dto.getSqlTemplateId() != null) {
            ThrowUtils.throwIf(sqlTemplateMapper.selectById(dto.getSqlTemplateId()) == null,
                    new BusinessException(ErrorCode.NOT_FOUND_ERROR, "关联 SQL 模板不存在: id=" + dto.getSqlTemplateId()));
        }

        String source = dto.getKnowledgeSource() == null ? KbKnowledgeSource.CODE.getValue() : dto.getKnowledgeSource();
        boolean llm = KbKnowledgeSource.LLM.getValue().equals(source);
        KbBizLink existing = bizLinkMapper.selectOne(new QueryWrapper<KbBizLink>()
                .eq("ds_id", dto.getDsId())
                .eq("biz_operation", dto.getBizOperation())
                .eq(dto.getSqlTemplateId() != null, "sql_template_id", dto.getSqlTemplateId())
                .isNull(dto.getSqlTemplateId() == null, "sql_template_id")
                .last("LIMIT 1"));

        Date now = new Date();
        KbBizLink row = new KbBizLink();
        row.setDsId(dto.getDsId());
        row.setBizOperation(dto.getBizOperation());
        row.setServiceClass(dto.getServiceClass());
        row.setDaoClass(dto.getDaoClass());
        row.setSqlTemplateId(dto.getSqlTemplateId());
        row.setRelatedTables(KbJsonUtils.toJson(dto.getRelatedTables()));
        row.setKnowledgeSource(source);
        row.setConfidence(dto.getConfidence() == null ? KbKnowledgeSource.defaultConfidenceOf(source) : dto.getConfidence());
        row.setEvidence(dto.getEvidence());
        if (llm) {
            row.setVerified(0);
            row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
        } else {
            row.setVerified(dto.getVerified() == null ? 0 : dto.getVerified());
            row.setStatus(blankToDefault(dto.getStatus(), KbKnowledgeStatus.DRAFT.getValue()));
        }

        if (existing == null) {
            row.setVersion(1);
            row.setHitCount(0);
            row.setErrorCount(0);
            row.setCreateTime(now);
            row.setUpdateTime(now);
            bizLinkMapper.insert(row);
        } else {
            if (!llm) {
                // 未显式传状态/校验/置信度时保留旧值
                if (dto.getStatus() == null) {
                    row.setStatus(existing.getStatus());
                }
                if (dto.getVerified() == null) {
                    row.setVerified(existing.getVerified());
                }
                if (dto.getConfidence() == null) {
                    row.setConfidence(existing.getConfidence());
                }
            } else {
                row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
                row.setVerified(0);
            }
            row.setVersion(existing.getVersion() + 1);
            row.setHitCount(existing.getHitCount());
            row.setErrorCount(existing.getErrorCount());
            row.setEvidence(appendDiffEvidence(existing.getEvidence(), dto.getEvidence(),
                    "v" + existing.getVersion() + "->" + row.getVersion(), null));
            row.setCreateTime(existing.getCreateTime());
            row.setUpdateTime(now);
            row.setId(existing.getId());
            bizLinkMapper.updateById(row);
        }
        KbSaveResultVO vo = buildSaveVO(row);
        if (llm) {
            vo.setReviewTaskId(reviewService.createTaskInternal(row.getDsId(),
                    KbReviewTaskType.LLM_INFERENCE.getValue(),
                    KbTargetObjectType.BIZ_LINK.getValue(),
                    targetJson(row.getId(), row.getBizOperation()), null,
                    "llm biz-link save auto-review"));
        }
        return vo;
    }

    // ==================== 接口8 Schema 批量同步 ====================

    public KbSchemaBatchVO schemaBatch(KbSchemaBatchDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(dto.getTables() == null || dto.getTables().isEmpty(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "tables 不能为空"));

        long savedTables = 0;
        long savedColumns = 0;
        long savedIndexes = 0;
        List<KbChangeSummaryVO> changes = new ArrayList<>();
        Set<String> reportedTables = new HashSet<>();
        for (KbTableSaveDTO item : dto.getTables()) {
            item.setDsId(dto.getDsId());
            // 保存前快照：表是否存在 + 该表既有列/索引集合（作为列级/索引级 diff 基准）
            KbTable beforeTable = findTableByUnique(item.getDsId(), item.getSchemaName(), item.getTableName());
            boolean existedBefore = beforeTable != null;
            Map<String, KbColumn> beforeColumns = new HashMap<>();
            Map<String, KbIndex> beforeIndexes = new HashMap<>();
            if (existedBefore) {
                List<KbColumn> cols = columnMapper.selectList(new QueryWrapper<KbColumn>()
                        .eq("ds_id", item.getDsId())
                        .eq("table_id", beforeTable.getId()));
                for (KbColumn c : cols) {
                    beforeColumns.put(c.getColumnName(), c);
                }
                // 索引级 diff 基准：仅当本次携带 indexes 时才查询 kb_index，
                // 未传时完全不触碰该表（spec A-2.8、零额外查询）
                if (item.getIndexes() != null) {
                    List<KbIndex> idxs = indexMapper.selectList(new QueryWrapper<KbIndex>()
                            .eq("ds_id", item.getDsId())
                            .eq("table_id", beforeTable.getId()));
                    for (KbIndex idx : idxs) {
                        beforeIndexes.put(idx.getIndexName(), idx);
                    }
                }
            }
            // 逐表保存（等同接口1+2）；索引通路仅在 item.indexes != null 时触发
            KbSaveResultVO result = saveTable(item);
            savedTables++;
            if (item.getColumns() != null) {
                savedColumns += item.getColumns().size();
            }
            if (item.getIndexes() != null) {
                savedIndexes += item.getIndexes().size();
            }
            // 每表先做列级 diff 摘要（入参列 vs 保存前既有 kb_column）：只要存在任一列
            // 新增/删除/类型变化就生成变更摘要，使“纯列删除”场景（表已存在、仅列减少、
            // 其余列元数据不变、structureChanged=false）也能如实返回 droppedColumns。
            // 索引级同理：入参未携带 indexes 时 fillIndexChanges 返回 false 且不查库。
            KbChangeSummaryVO summary = new KbChangeSummaryVO();
            summary.setTableName(item.getTableName());
            summary.setImpacts(result.getImpacted());
            boolean diffPresent = fillColumnChanges(summary, item, beforeColumns);
            boolean indexDiffPresent = fillIndexChanges(summary, item, beforeIndexes);
            boolean needSummary = diffPresent || indexDiffPresent
                    || Boolean.TRUE.equals(result.getStructureChanged())
                    || !existedBefore
                    || (result.getImpacted() != null && !result.getImpacted().isEmpty());
            if (needSummary && reportedTables.add(item.getTableName())) {
                changes.add(summary);
            }
        }

        long deprecated = 0;
        if (Boolean.TRUE.equals(dto.getFullSync())) {
            deprecated = deprecateMissingTables(dto.getDsId(), dto.getTables());
        }

        KbSchemaBatchVO vo = new KbSchemaBatchVO();
        vo.setSavedTables(savedTables);
        vo.setSavedColumns(savedColumns);
        vo.setSavedIndexes(savedIndexes);
        vo.setDeprecatedTables(deprecated);
        vo.setChanges(changes.isEmpty() ? null : changes);
        return vo;
    }

    // ==================== 接口9 软删除知识 ====================

    public KbSaveResultVO deprecate(KbDeprecateDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        KbTargetObjectType type = KbTargetObjectType.from(dto.getTargetObjectType());
        ThrowUtils.throwIf(type == null,
                new BusinessException(KbErrorCode.TARGET_TYPE_ILLEGAL, "targetObjectType 非法"));
        ThrowUtils.throwIf(dto.getObjectId() == null,
                new BusinessException(ErrorCode.PARAMS_ERROR, "objectId 不能为空"));

        KbKnowledgeRow row = rowRouter.selectByDsAndId(dto.getDsId(), type, dto.getObjectId());
        KbSaveResultVO vo = new KbSaveResultVO();
        vo.setObjectId(row.getId());
        if (KbKnowledgeStatus.DEPRECATED.getValue().equals(row.getStatus())) {
            vo.setStatus(KbKnowledgeStatus.DEPRECATED.getValue());
            return vo; // 幂等返回成功
        }

        String reason = dto.getReason() == null ? "deprecate by api" : dto.getReason();
        row.setStatus(KbKnowledgeStatus.DEPRECATED.getValue());
        row.setEvidence(appendEvidence(row.getEvidence(), reason));
        rowRouter.updateRow(type, row);
        vo.setStatus(KbKnowledgeStatus.DEPRECATED.getValue());

        // column 删除时父表 version+1
        if (type == KbTargetObjectType.COLUMN && row instanceof KbColumn column) {
            if (column.getTableId() != null) {
                KbTable parent = tableMapper.selectById(column.getTableId());
                if (parent != null) {
                    UpdateWrapper<KbTable> uw = new UpdateWrapper<>();
                    uw.eq("id", parent.getId()).setSql("version = version + 1")
                            .set("update_time", new Date());
                    tableMapper.update(null, uw);
                }
            }
        }

        // 表/字段被删时反查引用模板/链路，返回影响摘要（不自动删除引用方）
        if (type == KbTargetObjectType.TABLE || type == KbTargetObjectType.COLUMN) {
            String tableName = type == KbTargetObjectType.TABLE && row instanceof KbTable t ? t.getTableName()
                    : (row instanceof KbColumn c ? c.getTableName() : null);
            if (tableName != null) {
                List<KbImpactItemVO> impacted = impactByTable(dto.getDsId(), tableName);
                vo.setImpacted(impacted.isEmpty() ? null : impacted);
            }
        }
        return vo;
    }

    // ==================== 接口10 查询知识详情 ====================

    public KbDetailVO detail(KbDetailDTO dto) {
        KbTargetObjectType type = KbTargetObjectType.from(dto.getTargetObjectType());
        ThrowUtils.throwIf(type == null,
                new BusinessException(KbErrorCode.TARGET_TYPE_ILLEGAL, "targetObjectType 非法"));
        ThrowUtils.throwIf(dto.getObjectId() == null,
                new BusinessException(ErrorCode.PARAMS_ERROR, "objectId 不能为空"));

        KbKnowledgeRow row = rowRouter.selectById(type, dto.getObjectId());
        ThrowUtils.throwIf(row == null,
                new BusinessException(ErrorCode.NOT_FOUND_ERROR, "知识不存在: type=" + type.getValue()));

        KbDetailVO vo = new KbDetailVO();
        vo.setObjectType(type.getValue());
        vo.setObjectId(row.getId());
        vo.setContent(row);
        return vo;
    }

    // ==================== 内部方法 ====================

    /** 级联保存列并检测结构变更（新增/类型变化），返回是否发生结构变更 */
    private boolean cascadeColumns(KbTable parent, List<KbColumnItemDTO> columns, List<KbImpactItemVO> impacted) {
        boolean changed = false;
        for (KbColumnItemDTO c : columns) {
            ThrowUtils.throwIf(c.getColumnName() == null || c.getColumnName().isBlank(),
                    new BusinessException(ErrorCode.PARAMS_ERROR, "列名不能为空"));
            KbColumn existing = columnMapper.selectOne(new QueryWrapper<KbColumn>()
                    .eq("ds_id", parent.getDsId())
                    .eq("table_id", parent.getId())
                    .eq("column_name", c.getColumnName())
                    .last("LIMIT 1"));

            String source = c.getKnowledgeSource() == null ? KbKnowledgeSource.SCHEMA.getValue() : c.getKnowledgeSource();
            boolean llm = KbKnowledgeSource.LLM.getValue().equals(source);
            Date now = new Date();
            KbColumn row = new KbColumn();
            row.setDsId(parent.getDsId());
            row.setTableId(parent.getId());
            row.setDbType(parent.getDbType());
            row.setSchemaName(parent.getSchemaName());
            row.setTableName(parent.getTableName());
            row.setColumnName(c.getColumnName());
            row.setDataType(c.getDataType());
            row.setIsPk(c.getIsPk() == null ? 0 : c.getIsPk());
            row.setIsFk(c.getIsFk() == null ? 0 : c.getIsFk());
            row.setCommentText(c.getCommentText());
            row.setSemanticTags(KbJsonUtils.toJson(c.getSemanticTags()));
            row.setSampleStats(KbJsonUtils.toJson(c.getSampleStats()));
            row.setKnowledgeSource(source);
            row.setConfidence(c.getConfidence() == null ? KbKnowledgeSource.defaultConfidenceOf(source) : c.getConfidence());
            row.setEvidence(c.getEvidence());
            if (llm) {
                row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
                row.setVerified(0);
            } else {
                row.setStatus(blankToDefault(c.getStatus(), defaultAutoStatus(source)));
                row.setVerified(c.getVerified() == null ? defaultVerified(source, row.getStatus()) : c.getVerified());
            }

            if (existing == null) {
                changed = true;
                row.setVersion(1);
                row.setHitCount(0);
                row.setErrorCount(0);
                row.setCreateTime(now);
                row.setUpdateTime(now);
                columnMapper.insert(row);
            } else {
                boolean wasDeprecated = KbKnowledgeStatus.DEPRECATED.getValue().equals(existing.getStatus());
                if (llm) {
                    row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
                    row.setVerified(0);
                } else if (wasDeprecated) {
                    // 列再次出现：恢复 active（crawler 视为表结构最新）
                    row.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
                    row.setVerified(defaultVerified(source, KbKnowledgeStatus.ACTIVE.getValue()));
                } else {
                    // 未显式传状态/校验/置信度时保留旧值
                    if (c.getStatus() == null) {
                        row.setStatus(existing.getStatus());
                    }
                    if (c.getVerified() == null) {
                        row.setVerified(existing.getVerified());
                    }
                    if (c.getConfidence() == null) {
                        row.setConfidence(existing.getConfidence());
                    }
                }
                // 类型是否"实质"变化：签名补全（如 varchar → varchar(64)）视作等价、不计为变化
                // （复用 typeChanged 助手），避免升级后首次采集把所有既有列误判为结构变更，
                // 进而对全部引用模板/链路误降级（confidence×0.5 → needs_review）。
                // 主外键比较表达式与缺省语义保持原样不变。
                boolean typeDiff = typeChanged(existing, c)
                        || !eqInt(existing.getIsPk(), row.getIsPk())
                        || !eqInt(existing.getIsFk(), row.getIsFk());
                if (typeDiff) {
                    changed = true;
                }
                row.setVersion(existing.getVersion() + 1);
                row.setHitCount(existing.getHitCount());
                row.setErrorCount(existing.getErrorCount());
                row.setEvidence(appendDiffEvidence(existing.getEvidence(), c.getEvidence(),
                        "v" + existing.getVersion() + "->" + row.getVersion(), row.getCommentText()));
                row.setCreateTime(existing.getCreateTime());
                row.setUpdateTime(now);
                row.setId(existing.getId());
                columnMapper.updateById(row);
            }
        }

        if (changed) {
            impacted.addAll(impactByTable(parent.getDsId(), parent.getTableName()));
        }
        return changed;
    }

    private void insertColumn(KbTable parent, KbColumnItemDTO c) {
        ThrowUtils.throwIf(c.getColumnName() == null || c.getColumnName().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "列名不能为空"));
        String source = c.getKnowledgeSource() == null ? KbKnowledgeSource.SCHEMA.getValue() : c.getKnowledgeSource();
        boolean llm = KbKnowledgeSource.LLM.getValue().equals(source);
        Date now = new Date();
        KbColumn row = new KbColumn();
        row.setDsId(parent.getDsId());
        row.setTableId(parent.getId());
        row.setDbType(parent.getDbType());
        row.setSchemaName(parent.getSchemaName());
        row.setTableName(parent.getTableName());
        row.setColumnName(c.getColumnName());
        row.setDataType(c.getDataType());
        row.setIsPk(c.getIsPk() == null ? 0 : c.getIsPk());
        row.setIsFk(c.getIsFk() == null ? 0 : c.getIsFk());
        row.setCommentText(c.getCommentText());
        row.setSemanticTags(KbJsonUtils.toJson(c.getSemanticTags()));
        row.setSampleStats(KbJsonUtils.toJson(c.getSampleStats()));
        row.setKnowledgeSource(source);
        row.setConfidence(c.getConfidence() == null ? KbKnowledgeSource.defaultConfidenceOf(source) : c.getConfidence());
        row.setEvidence(c.getEvidence());
        if (llm) {
            row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
            row.setVerified(0);
        } else {
            row.setStatus(blankToDefault(c.getStatus(), defaultAutoStatus(source)));
            row.setVerified(c.getVerified() == null ? defaultVerified(source, row.getStatus()) : c.getVerified());
        }
        row.setVersion(1);
        row.setHitCount(0);
        row.setErrorCount(0);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        columnMapper.insert(row);
    }

    /**
     * 级联保存索引知识（kb_index），按唯一键 {@code uk_ds_table_index (ds_id, table_id, index_name)} 幂等 upsert。
     *
     * <p><b>幂等语义</b>：
     * <ul>
     *   <li>同值重复落库不新增行、{@code version} 不自增（仅刷新 {@code update_time}）；</li>
     *   <li>索引定义（列清单/唯一性/主键性/类型）或状态/校验位变化时 {@code version+1}，并更新 {@code index_columns}；</li>
     *   <li>本次入参缺失的既有<b>非 deprecated</b>索引置 {@code status='deprecated'}（软删，<b>不物理删除</b>）；</li>
     *   <li>{@code index_columns} 由 {@code KbJsonUtils.toJson(columns)} 生成（保持列顺序），超 1024 字符时
     *       保留前 N 列并追加 {@code "..."} 截断标记，同时在 {@code evidence} 追加 {@code truncated} 提示。</li>
     * </ul>
     * 本方法仅在调用方显式传入 {@code indexes} 时被调用，未传 = 完全不触碰 kb_index。</p>
     *
     * @param summary 索引级变更摘要（可空）；新增/软删索引名回填到 {@code addedIndexes}/{@code droppedIndexes}
     * @return 是否发生索引变化
     */
    private boolean cascadeIndexes(KbTable parent, List<KbIndexItemDTO> indexes, KbChangeSummaryVO summary) {
        boolean changed = false;
        Set<String> present = new HashSet<>();
        List<String> added = new ArrayList<>();
        for (KbIndexItemDTO item : indexes) {
            ThrowUtils.throwIf(item.getIndexName() == null || item.getIndexName().isBlank(),
                    new BusinessException(ErrorCode.PARAMS_ERROR, "索引名不能为空"));
            present.add(item.getIndexName());

            KbIndex existing = indexMapper.selectOne(new QueryWrapper<KbIndex>()
                    .eq("ds_id", parent.getDsId())
                    .eq("table_id", parent.getId())
                    .eq("index_name", item.getIndexName())
                    .last("LIMIT 1"));

            String source = item.getKnowledgeSource() == null
                    ? KbKnowledgeSource.SCHEMA.getValue() : item.getKnowledgeSource();
            boolean llm = KbKnowledgeSource.LLM.getValue().equals(source);
            Date now = new Date();

            // 有序列清单序列化 + 超长截断（不静默丢失顺序）
            String serialized = item.getColumns() == null ? null : KbJsonUtils.toJson(item.getColumns());
            boolean truncated = serialized != null && serialized.length() > INDEX_COLUMNS_MAX_LEN;
            String indexColumnsJson = truncated ? truncateIndexColumns(item.getColumns()) : serialized;
            if (indexColumnsJson == null || indexColumnsJson.isBlank()) {
                indexColumnsJson = "[]";
            }
            String baseEvidence = truncated ? appendEvidence(item.getEvidence(), "truncated") : item.getEvidence();

            KbIndex row = new KbIndex();
            row.setDsId(parent.getDsId());
            row.setTableId(parent.getId());
            row.setDbType(parent.getDbType());
            row.setSchemaName(parent.getSchemaName());
            row.setTableName(parent.getTableName());
            row.setIndexName(item.getIndexName());
            row.setIndexColumns(indexColumnsJson);
            row.setIsUnique(item.getIsUnique() == null ? 0 : item.getIsUnique());
            row.setIsPrimary(item.getIsPrimary() == null ? 0 : item.getIsPrimary());
            row.setIndexType(item.getIndexType());
            row.setKnowledgeSource(source);
            row.setConfidence(item.getConfidence() == null
                    ? KbKnowledgeSource.defaultConfidenceOf(source) : item.getConfidence());
            row.setEvidence(baseEvidence);
            if (llm) {
                row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
                row.setVerified(0);
            } else {
                row.setStatus(blankToDefault(item.getStatus(), defaultAutoStatus(source)));
                row.setVerified(item.getVerified() == null
                        ? defaultVerified(source, row.getStatus()) : item.getVerified());
            }

            if (existing == null) {
                changed = true;
                row.setVersion(1);
                row.setHitCount(0);
                row.setErrorCount(0);
                row.setCreateTime(now);
                row.setUpdateTime(now);
                indexMapper.insert(row);
                added.add(item.getIndexName());
            } else {
                boolean wasDeprecated = KbKnowledgeStatus.DEPRECATED.getValue().equals(existing.getStatus());
                if (llm) {
                    row.setStatus(KbKnowledgeStatus.DRAFT.getValue());
                    row.setVerified(0);
                } else if (wasDeprecated) {
                    // 索引再次出现：恢复 active（采集视为字典最新）
                    row.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
                    row.setVerified(item.getVerified() == null
                            ? defaultVerified(source, KbKnowledgeStatus.ACTIVE.getValue()) : item.getVerified());
                } else {
                    // 未显式传状态/校验/置信度时保留旧值（避免 upsert 把 active 打回 draft）
                    if (item.getStatus() == null) {
                        row.setStatus(existing.getStatus());
                    }
                    if (item.getVerified() == null) {
                        row.setVerified(existing.getVerified());
                    }
                    if (item.getConfidence() == null) {
                        row.setConfidence(existing.getConfidence());
                    }
                }
                // 「值变化」判定：仅索引定义/状态/校验位实质变化才 version+1；同值重复落库保持 version 不变
                boolean valueChanged = !eqStr(existing.getIndexColumns(), row.getIndexColumns())
                        || !eqInt(existing.getIsUnique(), row.getIsUnique())
                        || !eqInt(existing.getIsPrimary(), row.getIsPrimary())
                        || !eqStr(existing.getIndexType(), row.getIndexType())
                        || !eqStr(existing.getStatus(), row.getStatus())
                        || !eqInt(existing.getVerified(), row.getVerified());
                if (valueChanged) {
                    changed = true;
                    row.setVersion(existing.getVersion() + 1);
                    row.setEvidence(appendDiffEvidence(existing.getEvidence(), baseEvidence,
                            "v" + existing.getVersion() + "->" + (existing.getVersion() + 1), null));
                } else {
                    // 幂等：保留原 version/evidence，仅刷新 update_time
                    row.setVersion(existing.getVersion());
                    row.setEvidence(existing.getEvidence());
                }
                row.setHitCount(existing.getHitCount());
                row.setErrorCount(existing.getErrorCount());
                row.setCreateTime(existing.getCreateTime());
                row.setUpdateTime(now);
                row.setId(existing.getId());
                indexMapper.updateById(row);
            }
        }

        // 本次缺失的既有索引 → 软删 deprecated（不物理删除），并记入 droppedIndexes
        List<String> dropped = new ArrayList<>();
        List<KbIndex> stored = indexMapper.selectList(new QueryWrapper<KbIndex>()
                .eq("ds_id", parent.getDsId())
                .eq("table_id", parent.getId())
                .ne("status", KbKnowledgeStatus.DEPRECATED.getValue()));
        for (KbIndex old : stored) {
            if (present.contains(old.getIndexName())) {
                continue;
            }
            old.setStatus(KbKnowledgeStatus.DEPRECATED.getValue());
            old.setEvidence(appendEvidence(old.getEvidence(), "schema sync deprecate missing index"));
            old.setVersion(old.getVersion() + 1);
            old.setUpdateTime(new Date());
            indexMapper.updateById(old);
            dropped.add(old.getIndexName());
            changed = true;
        }

        if (summary != null) {
            summary.setAddedIndexes(added.isEmpty() ? null : added);
            summary.setDroppedIndexes(dropped.isEmpty() ? null : dropped);
        }
        return changed;
    }

    /**
     * 索引列清单超长截断：保留前 N 列并追加 {@code "..."} 截断标记（有序、不静默丢失顺序）。
     * 极端情况下单列名即超限时做硬截断并保留标记。
     */
    private String truncateIndexColumns(List<String> columns) {
        List<String> kept = new ArrayList<>();
        for (String c : columns) {
            List<String> candidate = new ArrayList<>(kept);
            candidate.add(c);
            candidate.add("...");
            if (KbJsonUtils.toJson(candidate).length() > INDEX_COLUMNS_MAX_LEN) {
                break;
            }
            kept.add(c);
        }
        List<String> marked = new ArrayList<>(kept);
        marked.add("...");
        String json = KbJsonUtils.toJson(marked);
        if (json.length() > INDEX_COLUMNS_MAX_LEN) {
            json = json.substring(0, INDEX_COLUMNS_MAX_LEN - 3) + "...";
        }
        return json;
    }

    /**
     * 填充变更摘要的索引级明细（新增/软删），并返回是否存在任一索引级 diff。
     *
     * <p>对比本次入参索引集合（item.indexes）与保存前库内既有 kb_index 集合：入参有而库内没有 →
     * addedIndexes；库内非 deprecated 有而本次入参没有 → droppedIndexes。索引落库动作由
     * {@link #cascadeIndexes} 完成，这里只负责 diff 摘要，不重复写库。
     * <b>入参未携带 indexes（null）时直接返回 false 且不产生任何查询</b>（既有调用方零影响）。</p>
     *
     * @return 是否存在任一索引级 diff（added/dropped 任一非空）
     */
    private boolean fillIndexChanges(KbChangeSummaryVO summary, KbTableSaveDTO item,
                                     Map<String, KbIndex> beforeIndexes) {
        if (item.getIndexes() == null) {
            return false;
        }
        List<String> added = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Set<String> incoming = new HashSet<>();
        for (KbIndexItemDTO idx : item.getIndexes()) {
            if (idx.getIndexName() == null || idx.getIndexName().isBlank()) {
                continue;
            }
            incoming.add(idx.getIndexName());
            if (!beforeIndexes.containsKey(idx.getIndexName())) {
                added.add(idx.getIndexName());
            }
        }
        for (Map.Entry<String, KbIndex> e : beforeIndexes.entrySet()) {
            if (KbKnowledgeStatus.DEPRECATED.getValue().equals(e.getValue().getStatus())) {
                continue;
            }
            if (!incoming.contains(e.getKey())) {
                dropped.add(e.getKey());
            }
        }
        summary.setAddedIndexes(added.isEmpty() ? null : added);
        summary.setDroppedIndexes(dropped.isEmpty() ? null : dropped);
        return !added.isEmpty() || !dropped.isEmpty();
    }

    /** 结构变更降级影响：调用置信度服务内部实现（createReview=false 保存联动场景）并返回摘要 */
    private List<KbImpactItemVO> impactByTable(String dsId, String tableName) {
        try {
            // 先查询受影响对象构建摘要
            List<KbImpactItemVO> impacted = new ArrayList<>();
            List<KbSqlTemplate> templates = sqlTemplateMapper.selectImpactedByTable(dsId, tableName);
            List<KbBizLink> links = bizLinkMapper.selectImpactedByTable(dsId, tableName);
            for (KbSqlTemplate t : templates) {
                impacted.add(new KbImpactItemVO(KbTargetObjectType.SQL_TEMPLATE.getValue(), t.getId(),
                        t.getBizAction() == null ? String.valueOf(t.getId()) : t.getBizAction()));
            }
            for (KbBizLink l : links) {
                impacted.add(new KbImpactItemVO(KbTargetObjectType.BIZ_LINK.getValue(), l.getId(),
                        l.getBizOperation() == null ? String.valueOf(l.getId()) : l.getBizOperation()));
            }
            // 实际降级（×0.5 → needs_review）
            confidenceService.degradeByAffectedTable(dsId, tableName, null, null, null, false);
            return impacted;
        } catch (Exception e) {
            log.warn("结构变更影响分析失败 table={}: {}", tableName, e.getMessage());
            throw new BusinessException(KbErrorCode.IMPACT_ANALYSIS_FAILED, "索引/反查失败");
        }
    }

    /**
     * 填充变更摘要的列级明细（新增/删除/类型变化），并返回是否存在任一列级 diff。
     *
     * <p>对比本次入参列集合（item.columns）与保存前库内既有 kb_column 集合：
     * 入参有而库内没有 → addedColumns；库内非 deprecated 有而本次入参没有 → droppedColumns；
     * 同名列但 data_type/is_pk/is_fk 变化 → changedColumns。保存动作由 saveTable 级联完成，
     * 这里只负责 diff 摘要，不重复写库。列删除仅在此如实上报 droppedColumns 供调用方决策，
     * 由 schema-crawler 依摘要调用 deprecate/影响分析，本方法不自动置 deprecated。</p>
     *
     * @return 是否存在任一 diff（added/dropped/changed 任一非空）
     */
    private boolean fillColumnChanges(KbChangeSummaryVO summary, KbTableSaveDTO item,
                                      Map<String, KbColumn> beforeColumns) {
        List<String> added = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        Map<String, KbColumnItemDTO> incoming = new HashMap<>();
        if (item.getColumns() != null) {
            for (KbColumnItemDTO c : item.getColumns()) {
                if (c.getColumnName() == null || c.getColumnName().isBlank()) {
                    continue;
                }
                incoming.put(c.getColumnName(), c);
                KbColumn old = beforeColumns.get(c.getColumnName());
                if (old == null) {
                    added.add(c.getColumnName());
                } else if (columnMetaChanged(old, c)) {
                    changed.add(c.getColumnName());
                }
            }
        }
        // 库内既有（非 deprecated）列本次未出现 → 视为删除
        for (Map.Entry<String, KbColumn> e : beforeColumns.entrySet()) {
            if (KbKnowledgeStatus.DEPRECATED.getValue().equals(e.getValue().getStatus())) {
                continue;
            }
            if (!incoming.containsKey(e.getKey())) {
                dropped.add(e.getKey());
            }
        }
        summary.setAddedColumns(added.isEmpty() ? null : added);
        summary.setDroppedColumns(dropped.isEmpty() ? null : dropped);
        summary.setChangedColumns(changed.isEmpty() ? null : changed);
        return !added.isEmpty() || !dropped.isEmpty() || !changed.isEmpty();
    }

    /** 同名列元数据是否变化（data_type/is_pk/is_fk），缺省 is_pk/is_fk 视作 0 */
    private boolean columnMetaChanged(KbColumn old, KbColumnItemDTO in) {
        boolean typeDiff = typeChanged(old, in);
        boolean pkChanged = !eqInt(old.getIsPk(), in.getIsPk() == null ? 0 : in.getIsPk());
        boolean fkChanged = !eqInt(old.getIsFk(), in.getIsFk() == null ? 0 : in.getIsFk());
        return typeDiff || pkChanged || fkChanged;
    }

    /**
     * 类型是否发生「实质」变化（data_type；主外键不并入本方法）。
     *
     * <p>「签名补全」不计为变化：落库口径升级后 {@code data_type} 由裸类型名（如 {@code varchar}）
     * 变为同基类型的签名（如 {@code varchar(64)}），二者语义等价。若计为变化，升级后首次采集会把
     * 所有既有列判为结构变更 → 引用模板/链路 confidence×0.5 大面积误降级为 needs_review
     * （见 spec.md 用户故事 A-3、api-contract.md 3.11）。</p>
     *
     * <p>「大小写/空白归一后相等」亦不计为变化：Oracle 系 Provider 取的是 {@code ALL_TAB_COLUMNS.DATA_TYPE}
     * 原始<b>大写</b>值（{@code DATE}/{@code CLOB}/{@code VARCHAR2}），而升级后落库值为
     * {@code ColumnTypeSignature.signature(...)}（统一小写归一），故 {@code DATE→date}、{@code CLOB→clob}、
     * {@code TIMESTAMP(6)→timestamp(6)} 等仅大小写/空白差异同样不得判为结构变化，否则升级首采误降级
     * 仍会在 Oracle 分支发生。</p>
     *
     * <p>真实变化仍判为变化：长度/精度变化（{@code varchar(64)→varchar(128)}、{@code decimal(10,2)→decimal(12,4)}）、
     * 基类型变化（{@code int→bigint}、{@code varchar→int(11)}）。注释（comment_text）不参与本判定。</p>
     */
    private boolean typeChanged(KbColumn old, KbColumnItemDTO in) {
        String oldType = old.getDataType();
        String newType = in.getDataType();
        if (eqStr(oldType, newType)) {
            return false;
        }
        if (ColumnTypeSignature.isSignatureBackfill(oldType, newType)) {
            return false;
        }
        // 大小写/空白归一后相等视为等价（Oracle 原始大写值 vs 签名小写归一值）：
        // 注意此处只做 trim + 忽略大小写，不做任何「基类型名相同」的粗粒度放宽，
        // 因此 varchar(64)→varchar(128)、int→bigint 仍判为变化。
        if (oldType != null && newType != null && oldType.trim().equalsIgnoreCase(newType.trim())) {
            return false;
        }
        return true;
    }

    /**
     * full_sync 缺失表置 deprecated（按 schema_name 分组限定范围）。
     *
     * <p>为何必须按 schema 限定：多库数据源（同一 dsId 的 spaceNames 含多个库，如
     * ["ai_server","rcagent_smoke"]）由 crawler 逐库调用 schemaBatch，每次入参 tables
     * 只包含当前这一个库的表。若此处仅按 ds_id 过滤全库表，则采集第二个库时第一个库的
     * 表会被误判为"缺失"并全部置 deprecated（实测导致 kb_table 出现 63 deprecated /
     * 1 active，L2 字典要求 verified=1 AND status='active' 因而查不到数据）。</p>
     *
     * <p>修复方式：先按入参 schemaName 分组得到 Map&lt;schemaName, Set&lt;tableName&gt;&gt;，
     * 然后对每个出现过的 schemaName，只查询该 ds_id + schema_name 下非 deprecated 的表，
     * 将不在本组 present 集合中的表置 deprecated；入参未出现的其它 schema 表行不做任何
     * 改动。schemaName 为 null 时用 isNull("schema_name") 限定，与 findTableByUnique 的
     * null 处理保持一致。单库数据源（仅 1 个 schema）结果与旧行为等价。</p>
     */
    private long deprecateMissingTables(String dsId, List<KbTableSaveDTO> tables) {
        // schemaName -> 本次 full_sync 入参中该 schema 下仍存在的表名集合（HashMap 允许 null key，承载无 schema 的库）
        Map<String, Set<String>> presentBySchema = new HashMap<>();
        for (KbTableSaveDTO t : tables) {
            if (t.getTableName() == null) {
                continue;
            }
            presentBySchema.computeIfAbsent(t.getSchemaName(), k -> new HashSet<>()).add(t.getTableName());
        }
        long deprecated = 0;
        for (Map.Entry<String, Set<String>> entry : presentBySchema.entrySet()) {
            String schemaName = entry.getKey();
            Set<String> present = entry.getValue();
            // 仅限定在本 schema 内比对，避免误伤其它库（多库 spaceNames 逐库采集场景）
            List<KbTable> rows = tableMapper.selectList(new QueryWrapper<KbTable>()
                    .eq("ds_id", dsId)
                    .ne("status", KbKnowledgeStatus.DEPRECATED.getValue())
                    .eq(schemaName != null, "schema_name", schemaName)
                    .isNull(schemaName == null, "schema_name"));
            for (KbTable t : rows) {
                if (!present.contains(t.getTableName())) {
                    t.setStatus(KbKnowledgeStatus.DEPRECATED.getValue());
                    t.setEvidence(appendEvidence(t.getEvidence(), "full_sync deprecate missing table"));
                    tableMapper.updateById(t);
                    deprecated++;
                }
            }
        }
        return deprecated;
    }

    private KbTable findTableByUnique(String dsId, String schemaName, String tableName) {
        return tableMapper.selectOne(new QueryWrapper<KbTable>()
                .eq("ds_id", dsId)
                .eq(schemaName != null, "schema_name", schemaName)
                .isNull(schemaName == null, "schema_name")
                .eq("table_name", tableName)
                .last("LIMIT 1"));
    }

    private KbSaveResultVO buildSaveVO(KbKnowledgeRow row) {
        KbSaveResultVO vo = new KbSaveResultVO();
        vo.setObjectId(row.getId());
        vo.setVersion(row.getVersion());
        vo.setStatus(row.getStatus());
        vo.setConfidence(row.getConfidence());
        return vo;
    }

    /** SCHEMA/CODE 自动事实来源默认 active（调用方可传 draft 覆盖） */
    private String defaultAutoStatus(String source) {
        if (KbKnowledgeSource.SCHEMA.getValue().equals(source)
                || KbKnowledgeSource.CODE.getValue().equals(source)) {
            return KbKnowledgeStatus.ACTIVE.getValue();
        }
        return KbKnowledgeStatus.DRAFT.getValue();
    }

    /** SCHEMA 来源默认 verified=1（自动事实），其他默认 0 */
    private int defaultVerified(String source, String status) {
        if (KbKnowledgeSource.SCHEMA.getValue().equals(source)
                && KbKnowledgeStatus.ACTIVE.getValue().equals(status)) {
            return 1;
        }
        return 0;
    }

    private void validateSourceStatus(String source, String status, BigDecimal confidence) {
        if (source != null && !source.isBlank()) {
            ThrowUtils.throwIf(!KbKnowledgeSource.isValid(source),
                    new BusinessException(ErrorCode.PARAMS_ERROR, "knowledge_source 非法"));
        }
        if (status != null && !status.isBlank()) {
            ThrowUtils.throwIf(!KbKnowledgeStatus.isValid(status),
                    new BusinessException(ErrorCode.PARAMS_ERROR, "status 非法"));
        }
        if (confidence != null
                && (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "confidence 需在 0~1 之间");
        }
    }

    private String blankToDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private boolean eqStr(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private boolean eqInt(Integer a, Integer b) {
        return a == null ? b == null : a.equals(b);
    }

    private String appendDiffEvidence(String oldEvidence, String newEvidence, String versionMark, String comment) {
        String base = oldEvidence == null ? "" : oldEvidence;
        StringBuilder sb = new StringBuilder(base);
        if (!base.isBlank()) {
            sb.append(" | ");
        }
        sb.append(versionMark).append(" diff: ev=").append(snippet(newEvidence));
        if (comment != null) {
            sb.append(" comment=").append(snippet(comment));
        }
        return sb.length() > 4000 ? sb.substring(0, 4000) : sb.toString();
    }

    private String appendEvidence(String evidence, String snippet) {
        String sep = evidence == null || evidence.isBlank() ? "" : " | ";
        String combined = (evidence == null ? "" : evidence) + sep + snippet;
        return combined.length() > 4000 ? combined.substring(0, 4000) : combined;
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    private String targetJson(Long id, String name) {
        com.alibaba.fastjson2.JSONObject obj = new com.alibaba.fastjson2.JSONObject();
        obj.put("objectId", id);
        obj.put("desc", name == null ? String.valueOf(id) : name);
        return obj.toJSONString();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "指纹计算失败");
        }
    }
}
