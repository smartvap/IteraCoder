package com.agenthub.ai.rcagent.kbcrawler.adapter;

import com.agenthub.ai.rcagent.kb.constant.KbKnowledgeSource;
import com.agenthub.ai.rcagent.kb.constant.KbKnowledgeStatus;
import com.agenthub.ai.rcagent.kb.constant.KbTargetObjectType;
import com.agenthub.ai.rcagent.kb.dto.KbBizLinkSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbColumnItemDTO;
import com.agenthub.ai.rcagent.kb.dto.KbColumnSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbDeprecateDTO;
import com.agenthub.ai.rcagent.kb.dto.KbIndexItemDTO;
import com.agenthub.ai.rcagent.kb.dto.KbRelationSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSampleStats;
import com.agenthub.ai.rcagent.kb.dto.KbSchemaBatchDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSchemaRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbStructureChangeDTO;
import com.agenthub.ai.rcagent.kb.dto.KbTableSaveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbTemplateSaveDTO;
import com.agenthub.ai.rcagent.kb.service.KbConfidenceService;
import com.agenthub.ai.rcagent.kb.service.KbKnowledgeService;
import com.agenthub.ai.rcagent.kb.service.KbRetrievalService;
import com.agenthub.ai.rcagent.kb.vo.KbColumnVO;
import com.agenthub.ai.rcagent.kb.vo.KbSaveResultVO;
import com.agenthub.ai.rcagent.kb.vo.KbSchemaBatchVO;
import com.agenthub.ai.rcagent.kb.vo.KbSchemaRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbStructureChangeVO;
import com.agenthub.ai.rcagent.kb.vo.KbTableVO;
import com.agenthub.ai.dbaccess.meta.ColumnTypeSignature;
import com.agenthub.ai.dbaccess.meta.IndexMeta;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * kb-schema-crawler → module-004（kb-storage-review）Java Service 交互层（brownfield 适配器）。
 *
 * <p>SDD api-contract 约束：本模块与 module-004 的全部写库交互集中在 {@link KbStorageReviewAdapter}，
 * 业务编排类禁止散落直接调用 module-004 Service；不臆造 module-004 不存在的方法，面向实际已编译类编程。</p>
 *
 * <p>采集产物状态口径（经用户确认，属对 kb-schema-crawler BR-002「采集一律 draft」的显式偏离）：
 * SCHEMA 字典事实（客观、可复现）采集即 {@code active} + {@code verified=1}，免人工审核，直接可被 L2 字典通道
 * 与 A 路结构化检索命中；CODE/RUNTIME/LLM 来源仍 draft 待审（BR-002 低置信不进上下文）。写库强制带 ds_id。</p>
 *
 * <p>说明：删除场景的软删需 module-004 {@code deprecate(objectId)} 的 objectId，
 * 适配器通过 module-004 {@link KbRetrievalService}（进程内 Java Service）按表/列定位 id 后执行，
 * 幂等：已 deprecated 或定位不到时直接跳过。</p>
 */
@Slf4j
@Component
public class KbStorageReviewAdapter {

    private final KbKnowledgeService knowledgeService;
    private final KbConfidenceService confidenceService;
    private final KbRetrievalService retrievalService;

    public KbStorageReviewAdapter(KbKnowledgeService knowledgeService,
                                  KbConfidenceService confidenceService,
                                  KbRetrievalService retrievalService) {
        this.knowledgeService = knowledgeService;
        this.confidenceService = confidenceService;
        this.retrievalService = retrievalService;
    }

    // ==================== Schema 表/列 批量写库（FR-001） ====================

    /**
     * Schema 表（含列）批量保存：逐表转 module-004 DTO 后经 schemaBatch 落库。
     *
     * @param dsId      数据源
     * @param dbType    库类型（DbType.code 字符串）
     * @param schemaName Schema
     * @param tables    中立表元数据（含列）
     * @param fullSync  true=全量（缺失表置 deprecated 由 module-004 处理）；false=增量
     * @return module-004 批量结果
     */
    public KbSchemaBatchVO saveSchemaTables(String dsId, String dbType, String schemaName,
                                            List<TableMeta> tables, boolean fullSync) {
        if (tables == null || tables.isEmpty()) {
            return null;
        }
        KbSchemaBatchDTO dto = new KbSchemaBatchDTO();
        dto.setDsId(dsId);
        dto.setFullSync(fullSync);
        List<KbTableSaveDTO> tableDtos = new ArrayList<>(tables.size());
        for (TableMeta table : tables) {
            tableDtos.add(toTableSaveDTO(dsId, dbType, schemaName, table));
        }
        dto.setTables(tableDtos);
        return knowledgeService.schemaBatch(dto);
    }

    /** 单表（含列）保存：供变更检测对新增/修改表精确落库或独立保存 */
    public KbSaveResultVO saveTable(String dsId, String dbType, String schemaName, TableMeta table) {
        return knowledgeService.saveTable(toTableSaveDTO(dsId, dbType, schemaName, table));
    }

    private KbTableSaveDTO toTableSaveDTO(String dsId, String dbType, String schemaName, TableMeta table) {
        KbTableSaveDTO dto = new KbTableSaveDTO();
        dto.setDsId(dsId);
        dto.setDbType(dbType);
        dto.setSchemaName(schemaName);
        dto.setTableName(table.getTableName());
        dto.setTableType(table.getTableType());
        dto.setCommentText(table.getTableComment());
        // SCHEMA 字典事实免人工审核：显式落 active + verified=1（用户确认口径，偏离 BR-002「采集一律 draft」）。
        // 必须显式传 active（不可留 null）：module-004 对「已存在且非 deprecated」的行在 status=null 时保留旧值，
        // 显式传值才能让历史 draft 行在下次采集时被提升为 active。
        dto.setKnowledgeSource(KbKnowledgeSource.SCHEMA.getValue());
        dto.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
        dto.setVerified(1);
        dto.setEvidence("kb-schema-crawler;snapshot");
        if (table.getRowCountEstimate() != null) {
            KbSampleStats stats = new KbSampleStats();
            stats.setRowEstimate(table.getRowCountEstimate());
            dto.setSampleStats(stats);
        }
        if (table.getColumns() != null && !table.getColumns().isEmpty()) {
            List<KbColumnItemDTO> columnDtos = new ArrayList<>(table.getColumns().size());
            for (com.agenthub.ai.dbaccess.meta.ColumnMeta c : table.getColumns()) {
                KbColumnItemDTO item = new KbColumnItemDTO();
                item.setColumnName(c.getColumnName());
                // 落库口径 = 类型签名（列名+长度/精度），与快照指纹 columnsHashOf 共用唯一实现
                item.setDataType(ColumnTypeSignature.signature(c));
                item.setIsPk(c.getIsPrimaryKey() == null ? 0 : c.getIsPrimaryKey());
                item.setIsFk(c.getIsForeignKey() == null ? 0 : c.getIsForeignKey());
                item.setCommentText(c.getColumnComment());
                item.setKnowledgeSource(KbKnowledgeSource.SCHEMA.getValue());
                // 列侧同表侧口径：SCHEMA 客观字典事实显式落 active + verified=1（用户确认口径）
                item.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
                item.setVerified(1);
                item.setEvidence("kb-schema-crawler;snapshot");
                columnDtos.add(item);
            }
            dto.setColumns(columnDtos);
        }
        // 索引清单映射：仅当采集侧带回非空索引时才下发。
        // indexes=null 时 module-004 完全不触碰 kb_index（既有调用方零影响）；列映射保持不变。
        if (table.getIndexes() != null && !table.getIndexes().isEmpty()) {
            List<KbIndexItemDTO> indexDtos = new ArrayList<>(table.getIndexes().size());
            for (IndexMeta idx : table.getIndexes()) {
                KbIndexItemDTO item = new KbIndexItemDTO();
                item.setIndexName(idx.getIndexName());
                item.setColumns(idx.getColumns());
                item.setIsUnique(idx.getIsUnique() == null ? 0 : idx.getIsUnique());
                item.setIsPrimary(idx.getIsPrimary() == null ? 0 : idx.getIsPrimary());
                item.setIndexType(idx.getIndexType());
                // H9 口径不回退（护栏 G-07）：SCHEMA 客观字典事实显式落 active + verified=1
                item.setKnowledgeSource(KbKnowledgeSource.SCHEMA.getValue());
                item.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
                item.setVerified(1);
                item.setEvidence("kb-schema-crawler;snapshot");
                indexDtos.add(item);
            }
            dto.setIndexes(indexDtos);
        }
        return dto;
    }

    // ==================== Mapper 静态解析补充 kb_column 注释（FR-003） ====================

    /**
     * 用 Mapper resultMap 语义补充 kb_column 注释（仅补充语义，不改变列 dataType/is_pk 等事实，
     * 通过检索定位到既有列并回填其 data_type，避免 module-004 将空 dataType 误判为结构变更）。
     *
     * @return true=已补充/命中；false=列不存在或未定位到跳过（不影响整体）
     */
    public boolean supplementColumnComment(String dsId, String schemaName, String tableName,
                                           String columnName, String commentSupplement, String evidence) {
        if (commentSupplement == null || commentSupplement.isBlank()) {
            return false;
        }
        try {
            KbSchemaRetrievalVO search = schemaRetrieve(dsId, tableName);
            KbColumnVO column = null;
            if (search != null && search.getColumns() != null) {
                for (KbColumnVO c : search.getColumns()) {
                    if (tableName.equalsIgnoreCase(c.getTableName())
                            && columnName.equalsIgnoreCase(c.getColumnName())) {
                        column = c;
                        break;
                    }
                }
            }
            if (column == null) {
                return false;
            }
            KbColumnSaveDTO dto = new KbColumnSaveDTO();
            dto.setDsId(dsId);
            dto.setSchemaName(schemaName);
            dto.setTableName(tableName);
            dto.setColumnName(columnName);
            // 回填字典已有类型，避免空 dataType 被 module-004 当作类型变化触发结构联动
            dto.setDataType(column.getDataType());
            // 注释补充仅更新 comment_text；status/verified/confidence 不传，module-004 保留旧值
            dto.setCommentText(commentSupplement);
            dto.setEvidence(evidence);
            KbSaveResultVO vo = knowledgeService.saveColumn(dto);
            return vo != null;
        } catch (Exception e) {
            // 父表不存在/列不存在等：跳过补充，不阻断整轮 Mapper 解析
            log.debug("kbcrawler: 列注释补充跳过 dsId={} table={} column={}: {}",
                    dsId, tableName, columnName, e.getMessage());
            return false;
        }
    }

    // ==================== 运行时采集写 kb_sql_template / kb_relation（FR-004） ====================

    /**
     * 保存/累加 SQL 模板（指纹幂等：同指纹重复上报 module-004 内部 freq 累加）。
     */
    public KbSaveResultVO saveTemplate(String dsId, String dbType, String normalizedSql,
                                       String bizAction, List<String> involvedTables,
                                       int freq, String evidence) {
        KbTemplateSaveDTO dto = new KbTemplateSaveDTO();
        dto.setDsId(dsId);
        dto.setDbType(dbType);
        dto.setBizAction(bizAction);
        dto.setNormalizedSql(normalizedSql);
        dto.setInvolvedTables(involvedTables);
        dto.setFreq(freq);
        dto.setKnowledgeSource(KbKnowledgeSource.RUNTIME.getValue());
        dto.setStatus(KbKnowledgeStatus.DRAFT.getValue());
        dto.setVerified(0);
        dto.setEvidence(evidence);
        return knowledgeService.saveTemplate(dto);
    }

    /**
     * 保存表关系（RUNTIME_JOIN join 证据，FR-002/FR-004）。
     */
    public KbSaveResultVO saveRelation(String dsId, String dbType, String tableA, String columnA,
                                       String tableB, String columnB, String relationType,
                                       List<List<String>> joinFields, String evidence) {
        KbRelationSaveDTO dto = new KbRelationSaveDTO();
        dto.setDsId(dsId);
        dto.setDbType(dbType);
        dto.setTableA(tableA);
        dto.setColumnA(columnA);
        dto.setTableB(tableB);
        dto.setColumnB(columnB);
        dto.setJoinFields(joinFields);
        dto.setRelationType(relationType);
        dto.setRelationSource("RUNTIME_JOIN");
        dto.setKnowledgeSource(KbKnowledgeSource.RUNTIME.getValue());
        dto.setStatus(KbKnowledgeStatus.DRAFT.getValue());
        dto.setVerified(0);
        dto.setEvidence(evidence);
        return knowledgeService.saveRelation(dto);
    }

    // ==================== Mapper 静态解析写 kb_biz_link（FR-003） ====================

    /**
     * 保存业务链路（service→dao→sql，CODE 来源 draft，BR-002）。
     */
    public KbSaveResultVO saveBizLink(String dsId, String bizOperation, String serviceClass,
                                      String daoClass, List<String> relatedTables, String evidence) {
        KbBizLinkSaveDTO dto = new KbBizLinkSaveDTO();
        dto.setDsId(dsId);
        dto.setBizOperation(bizOperation);
        dto.setServiceClass(serviceClass);
        dto.setDaoClass(daoClass);
        dto.setRelatedTables(relatedTables);
        dto.setKnowledgeSource(KbKnowledgeSource.CODE.getValue());
        dto.setStatus(KbKnowledgeStatus.DRAFT.getValue());
        dto.setVerified(0);
        dto.setEvidence(evidence);
        return knowledgeService.saveBizLink(dto);
    }

    // ==================== 删除软删（FR-005，deprecate 需 objectId） ====================

    /**
     * 软删表（TABLE_DROP）：经 module-004 检索定位表 id 后 deprecate；幂等跳过已 deprecated/未收录。
     */
    public boolean deprecateTable(String dsId, String tableName, String reason) {
        Long tableId = locateTableId(dsId, tableName);
        if (tableId == null) {
            return false;
        }
        KbDeprecateDTO dto = new KbDeprecateDTO();
        dto.setDsId(dsId);
        dto.setTargetObjectType(KbTargetObjectType.TABLE.getValue());
        dto.setObjectId(tableId);
        dto.setReason(reason);
        try {
            knowledgeService.deprecate(dto);
            return true;
        } catch (Exception e) {
            log.warn("kbcrawler: 表软删失败 dsId={} table={}: {}", dsId, tableName, e.getMessage());
            return false;
        }
    }

    /**
     * 软删列（COLUMN_DROP）：经 module-004 检索定位列 id 后 deprecate；幂等跳过已 deprecated/未收录。
     */
    public boolean deprecateColumn(String dsId, String tableName, String columnName, String reason) {
        Long columnId = locateColumnId(dsId, tableName, columnName);
        if (columnId == null) {
            return false;
        }
        KbDeprecateDTO dto = new KbDeprecateDTO();
        dto.setDsId(dsId);
        dto.setTargetObjectType(KbTargetObjectType.COLUMN.getValue());
        dto.setObjectId(columnId);
        dto.setReason(reason);
        try {
            knowledgeService.deprecate(dto);
            return true;
        } catch (Exception e) {
            log.warn("kbcrawler: 列软删失败 dsId={} table={} column={}: {}", dsId, tableName, columnName, e.getMessage());
            return false;
        }
    }

    /** 定位表 id（module-004 schema 检索，includeDraft 看草稿态） */
    private Long locateTableId(String dsId, String tableName) {
        KbSchemaRetrievalVO vo = schemaRetrieve(dsId, tableName);
        if (vo == null || vo.getTables() == null) {
            return null;
        }
        for (KbTableVO t : vo.getTables()) {
            if (tableName.equalsIgnoreCase(t.getTableName())) {
                return t.getId();
            }
        }
        return null;
    }

    /** 定位列 id */
    private Long locateColumnId(String dsId, String tableName, String columnName) {
        KbSchemaRetrievalVO vo = schemaRetrieve(dsId, tableName);
        if (vo == null || vo.getColumns() == null) {
            return null;
        }
        for (KbColumnVO c : vo.getColumns()) {
            if (tableName.equalsIgnoreCase(c.getTableName())
                    && columnName.equalsIgnoreCase(c.getColumnName())) {
                return c.getId();
            }
        }
        return null;
    }

    private KbSchemaRetrievalVO schemaRetrieve(String dsId, String tableName) {
        KbSchemaRetrievalDTO query = new KbSchemaRetrievalDTO();
        query.setDsId(dsId);
        query.setTableNames(Collections.singletonList(tableName));
        query.setIncludeDraft(true);
        query.setTopK(100);
        try {
            return retrievalService.retrieveSchema(query);
        } catch (Exception e) {
            log.debug("kbcrawler: schema 检索定位失败 dsId={} table={}: {}", dsId, tableName, e.getMessage());
            return null;
        }
    }

    // ==================== 结构变更影响（FR-005，联动 module-004 ×0.5 → needs_review） ====================

    /**
     * 结构变更影响分析：调 module-004 KbConfidenceService.structureChange，
     * 引用该表/列的模板与链路 confidence×0.5 → needs_review，并返回影响对象统计。
     */
    public KbStructureChangeVO structureChangeImpact(String dsId, String affectedTable,
                                                     List<String> affectedColumns,
                                                     String changeType, String evidence) {
        KbStructureChangeDTO dto = new KbStructureChangeDTO();
        dto.setDsId(dsId);
        dto.setAffectedTable(affectedTable);
        dto.setAffectedColumns(affectedColumns);
        dto.setChangeType(changeType);
        dto.setEvidence(evidence);
        return confidenceService.structureChange(dto);
    }

    /** 常用来源名（供日志/evidence，不携带敏感） */
    public static String sourceTag() {
        return "kb-schema-crawler";
    }
}
