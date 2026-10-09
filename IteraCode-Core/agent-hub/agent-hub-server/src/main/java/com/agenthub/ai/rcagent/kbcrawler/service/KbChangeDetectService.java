package com.agenthub.ai.rcagent.kbcrawler.service;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.meta.IndexMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.provider.MetadataProvider;
import com.agenthub.ai.dbaccess.provider.MetadataProviderRegistry;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.rcagent.kbcrawler.adapter.KbStorageReviewAdapter;
import com.agenthub.ai.rcagent.kbcrawler.constant.KbCrawlerConstant;
import com.agenthub.ai.rcagent.kbcrawler.entity.KbCrawlCursor;
import com.agenthub.ai.rcagent.kbcrawler.entity.KbCrawlSchemaSnapshot;
import com.agenthub.ai.rcagent.kbcrawler.mapper.KbCrawlCursorMapper;
import com.agenthub.ai.rcagent.kbcrawler.mapper.KbCrawlSchemaSnapshotMapper;
import com.agenthub.ai.rcagent.kbcrawler.model.ChangeDetectDiff;
import com.agenthub.ai.rcagent.kbcrawler.model.SchemaSnapshotVO;
import com.agenthub.ai.rcagent.kbcrawler.vo.ChangeDetectResultVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 变更检测服务（FR-005）：diff kb_crawl_schema_snapshot 与当前采集 Schema，
 * 新增 insert draft / 修改 update + 结构影响 ×0.5 / 删除 deprecate / 统计 refresh，
 * 经 {@link KbStorageReviewAdapter} 联动 module-004 needs_review。
 *
 * <p>无 SCHEMA_FULL 基线快照时抛 44006（先跑全量）。Provider 不暴露 last_ddl_time 时
 * 走 columns_hash 全比对识别结构变化。</p>
 */
@Slf4j
@Service
public class KbChangeDetectService {

    private final DataSourceRegistry dataSourceRegistry;
    private final MetadataProviderRegistry providerRegistry;
    private final KbCrawlSchemaSnapshotMapper snapshotMapper;
    private final KbCrawlCursorMapper cursorMapper;
    private final KbSchemaCrawlService schemaCrawlService;
    private final KbStorageReviewAdapter adapter;

    public KbChangeDetectService(DataSourceRegistry dataSourceRegistry,
                                 MetadataProviderRegistry providerRegistry,
                                 KbCrawlSchemaSnapshotMapper snapshotMapper,
                                 KbCrawlCursorMapper cursorMapper,
                                 KbSchemaCrawlService schemaCrawlService,
                                 KbStorageReviewAdapter adapter) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.providerRegistry = providerRegistry;
        this.snapshotMapper = snapshotMapper;
        this.cursorMapper = cursorMapper;
        this.schemaCrawlService = schemaCrawlService;
        this.adapter = adapter;
    }

    /**
     * 执行变更检测并落地处置动作。
     *
     * @param dsId     数据源
     * @param evidence 触发证据（如 release_tag）
     * @return 变更条目 + 受影响对象数
     */
    public ChangeDetectResultVO detectChanges(String dsId, String evidence) {
        DsConfigView config = resolveDsConfig(dsId);
        DbType dbType = DbType.parse(config.getDbType());
        MetadataProvider provider = resolveProvider(dbType);

        // 候选库集合：spaceNames 非空按多库逐库比对（各库独立 diff）；否则回落 spaceName 单库（兼容旧行为）
        List<String> spaces = resolveDetectSpaces(config);
        if (spaces.isEmpty()) {
            throw new BusinessException(KbCrawlerConstant.DS_NOT_REGISTERED, "数据源未配置可访问库");
        }

        List<ChangeDetectDiff> diffs = new ArrayList<>();
        long impacted = 0;
        for (String space : spaces) {
            // 1. 按库基线校验：该库须有 SCHEMA_FULL_HASH 游标或该库快照行，
            //    基线按 space 隔离，禁止用其它库的基线放行（多库下 SCHEMA_FULL_HASH 不再互相覆盖）
            boolean baseline = schemaCrawlService.getCursorValue(dsId, space, "SCHEMA_FULL_HASH") != null
                    || schemaCrawlService.countSnapshots(dsId, space) > 0;
            if (!baseline) {
                throw new BusinessException(KbCrawlerConstant.NO_SNAPSHOT_BASELINE,
                        "库[" + space + "]尚未完成 SCHEMA_FULL，缺少变更检测基线快照");
            }
            impacted += detectSpace(dsId, dbType, provider, space, evidence, diffs);
        }

        ChangeDetectResultVO vo = new ChangeDetectResultVO();
        vo.setDiffs(diffs);
        vo.setImpactedObjects(impacted);
        return vo;
    }

    /**
     * 解析本次变更检测的候选库集合（与 {@code KbSchemaCrawlService.resolveCrawlSpaces} 同口径）：
     * {@code spaceNames} 非空取去重集合；否则回落 {@code spaceName}；blank 返回空列表。
     */
    private List<String> resolveDetectSpaces(DsConfigView config) {
        List<String> spaces = new ArrayList<>();
        if (config == null) {
            return spaces;
        }
        List<String> names = config.getSpaceNames();
        if (names != null && !names.isEmpty()) {
            for (String name : names) {
                if (name == null) {
                    continue;
                }
                String trimmed = name.trim();
                if (trimmed.isEmpty() || spaces.contains(trimmed)) {
                    continue;
                }
                spaces.add(trimmed);
            }
            return spaces;
        }
        String single = config.getSpaceName();
        if (single != null && !single.isBlank()) {
            spaces.add(single.trim());
        }
        return spaces;
    }

    /**
     * 单库变更检测：采集当前 Schema → 与快照 diff → 处置动作 → 刷新该库快照/游标。
     * 快照读取与刷新均带 schema_name 边界，各库独立比对，互不覆盖。
     *
     * @return 受影响对象数
     */
    private long detectSpace(String dsId, DbType dbType, MetadataProvider provider,
                             String space, String evidence, List<ChangeDetectDiff> diffs) {
        // 2. 采集当前 Schema（开启外键/索引采集：外键使列级 isFk 变化参与既有 COLUMN_TYPE_CHANGE 判定）
        MetadataRequest request = MetadataRequest.builder()
                .dsId(dsId)
                .spaceName(space)
                .includeColumns(true)
                .includeStats(false)
                .includeForeignKeys(true)
                .includeIndexes(true)
                .build();
        List<TableMeta> currentTables;
        try {
            currentTables = provider.listTables(request);
        } catch (DbAccessException e) {
            throw new BusinessException(KbCrawlerConstant.DS_NOT_REGISTERED, "数据源访问失败，请确认只读账号权限");
        }
        if (currentTables == null) {
            currentTables = Collections.emptyList();
        }
        Map<String, TableMeta> currentMap = new LinkedHashMap<>();
        for (TableMeta t : currentTables) {
            currentMap.put(t.getTableName(), t);
        }

        // 3. 读取基线快照（仅当前库，避免多库同名表互相覆盖）
        Map<String, KbCrawlSchemaSnapshot> baselineMap = new LinkedHashMap<>();
        List<KbCrawlSchemaSnapshot> snapshots = snapshotMapper.selectList(
                new QueryWrapper<KbCrawlSchemaSnapshot>()
                        .eq("ds_id", dsId)
                        .eq("schema_name", space));
        for (KbCrawlSchemaSnapshot snap : snapshots) {
            baselineMap.put(snap.getTableName(), snap);
        }

        // 4. diff
        long impacted = 0;
        for (Map.Entry<String, TableMeta> e : currentMap.entrySet()) {
            String tableName = e.getKey();
            TableMeta current = e.getValue();
            KbCrawlSchemaSnapshot old = baselineMap.get(tableName);
            if (old == null) {
                // 新增表：insert draft
                ChangeDetectDiff d = newDiff(dsId, "TABLE_ADD", tableName, null, "insert", 1,
                        ev("TABLE_ADD 新表出现", evidence));
                diffs.add(d);
                try {
                    adapter.saveTable(dsId, dbType.getCode(), space, current);
                } catch (Exception ex) {
                    log.warn("kbcrawler: 变更-新表写库失败 {}: {}", tableName, ex.getMessage());
                }
                continue;
            }
            String currentHash = SchemaSnapshotVO.columnsHashOf(current);
            if (!currentHash.equals(old.getColumnsHash())) {
                // 结构修改：列级 diff 逐条
                List<ChangeDetectDiff> colDiffs = diffColumns(dsId, tableName, old, current, evidence);
                diffs.addAll(colDiffs);
                boolean columnChanged = !colDiffs.isEmpty();
                ChangeDetectDiff tableDiff = newDiff(dsId, "TABLE_MODIFY", tableName, null, "update",
                        1, ev("columns_hash diff:" + old.getColumnsHash() + "->" + currentHash, evidence));
                diffs.add(tableDiff);
                try {
                    adapter.saveTable(dsId, dbType.getCode(), space, current);
                    if (columnChanged) {
                        // 结构影响按列变更类型分治：类型变化/新增列→COLUMN_TYPE；列删除→COLUMN_DROP，
                        // 引用被删列的模板/链路同样 ×0.5→needs_review（FR-005）
                        List<String> typeImpactColumns = new ArrayList<>();
                        List<String> dropImpactColumns = new ArrayList<>();
                        for (ChangeDetectDiff cd : colDiffs) {
                            if (cd.getTargetColumn() == null) {
                                continue;
                            }
                            if ("COLUMN_DROP".equals(cd.getChangeType())) {
                                dropImpactColumns.add(cd.getTargetColumn());
                            } else {
                                typeImpactColumns.add(cd.getTargetColumn());
                            }
                        }
                        if (!typeImpactColumns.isEmpty()) {
                            var typeVo = adapter.structureChangeImpact(dsId, tableName,
                                    typeImpactColumns, "COLUMN_TYPE", tableDiff.getEvidence());
                            impacted += sumImpact(typeVo);
                        }
                        if (!dropImpactColumns.isEmpty()) {
                            var dropVo = adapter.structureChangeImpact(dsId, tableName,
                                    dropImpactColumns, "COLUMN_DROP", tableDiff.getEvidence());
                            impacted += sumImpact(dropVo);
                        }
                    }
                } catch (Exception ex) {
                    log.warn("kbcrawler: 变更-表修改写库失败 {}: {}", tableName, ex.getMessage());
                }
            } else {
                // 列指纹未变：先识别索引清单变化（INDEX_CHANGE），再识别统计/表级元数据变化。
                // 表无任何索引（视图/临时表等）→ 天然不产生 INDEX_CHANGE（避免对无索引表刷出噪声 diff）。
                boolean hasIndexes = current.getIndexes() != null && !current.getIndexes().isEmpty();
                if (hasIndexes) {
                    String oldIndexHash = indexHashFromMetaJson(old.getTableMetaJson());
                    String currentIndexHash = SchemaSnapshotVO.indexHashOf(current);
                    if (!Objects.equals(currentIndexHash, oldIndexHash)) {
                        // 索引变化不降级：structureImpact=0，**不调用** structureChangeImpact（护栏 G-05）
                        ChangeDetectDiff d = newDiff(dsId, "INDEX_CHANGE", tableName, null, "update", 0,
                                ev("index_hash diff:" + oldIndexHash + "->" + currentIndexHash, evidence));
                        diffs.add(d);
                        try {
                            // 刷新 kb_index（saveTable 内部按 indexName upsert）
                            adapter.saveTable(dsId, dbType.getCode(), space, current);
                        } catch (Exception ex) {
                            log.warn("kbcrawler: 变更-索引刷新写库失败 {}: {}", tableName, ex.getMessage());
                        }
                    }
                }
                // 统计/表级元数据变化（注释等）：仅 refresh stats / 注释，不触发 needs_review
                boolean statsChanged = current.getRowCountEstimate() != null
                        && old.getRowCountEstimate() != null
                        && !current.getRowCountEstimate().equals(old.getRowCountEstimate());
                if (statsChanged) {
                    ChangeDetectDiff d = newDiff(dsId, "STATS_CHANGE", tableName, null,
                            "refresh_stats", 0, ev("row_estimate diff", evidence));
                    diffs.add(d);
                }
            }
        }
        // 删除：快照有、当前无 → deprecate
        for (Map.Entry<String, KbCrawlSchemaSnapshot> e : baselineMap.entrySet()) {
            if (!currentMap.containsKey(e.getKey())) {
                ChangeDetectDiff d = newDiff(dsId, "TABLE_DROP", e.getKey(), null,
                        "deprecate", 1, ev("快照存在但当前采集缺失", evidence));
                diffs.add(d);
                try {
                    if (adapter.deprecateTable(dsId, e.getKey(), "change-detect table missing")) {
                        var vo = adapter.structureChangeImpact(dsId, e.getKey(), null,
                                "TABLE_DROP", d.getEvidence());
                        impacted += sumImpact(vo);
                    }
                } catch (Exception ex) {
                    log.warn("kbcrawler: 变更-表软删失败 {}: {}", e.getKey(), ex.getMessage());
                }
            }
        }

        // 5. 刷新快照 + 游标（仅当前库，避免多库互相覆盖/误删）
        refreshSnapshots(dsId, space, currentTables);
        refreshCursor(dsId, space, currentTables);
        return impacted;
    }

    /** 列级 diff：old snapshot(JSON) vs current TableMeta */
    private List<ChangeDetectDiff> diffColumns(String dsId, String tableName,
                                               KbCrawlSchemaSnapshot old, TableMeta current,
                                               String evidence) {
        List<ChangeDetectDiff> diffs = new ArrayList<>();
        Map<String, String> oldCols = new HashMap<>();
        JSONObject oldMeta = JSON.parseObject(old.getTableMetaJson() == null ? "{}" : old.getTableMetaJson());
        JSONArray oldArr = oldMeta == null ? null : oldMeta.getJSONArray("columns");
        if (oldArr != null) {
            for (int i = 0; i < oldArr.size(); i++) {
                JSONObject o = oldArr.getJSONObject(i);
                if (o != null) {
                    oldCols.put(o.getString("columnName"), o.getString("dataType"));
                }
            }
        }
        Map<String, String> newCols = new LinkedHashMap<>();
        if (current.getColumns() != null) {
            for (com.agenthub.ai.dbaccess.meta.ColumnMeta c : current.getColumns()) {
                newCols.put(c.getColumnName(), c.getDataType());
            }
        }
        // 新增列 / 类型变化
        for (Map.Entry<String, String> e : newCols.entrySet()) {
            String col = e.getKey();
            if (!oldCols.containsKey(col)) {
                ChangeDetectDiff d = newDiff(dsId, "COLUMN_ADD", tableName, col, "insert", 1,
                        ev("新列 " + col, evidence));
                diffs.add(d);
            } else if (oldCols.get(col) != null && !oldCols.get(col).equalsIgnoreCase(e.getValue())) {
                ChangeDetectDiff d = newDiff(dsId, "COLUMN_TYPE_CHANGE", tableName, col, "update", 1,
                        ev("列类型 " + oldCols.get(col) + "->" + e.getValue(), evidence));
                diffs.add(d);
            }
        }
        // 删除列
        for (Map.Entry<String, String> e : oldCols.entrySet()) {
            if (!newCols.containsKey(e.getKey())) {
                ChangeDetectDiff d = newDiff(dsId, "COLUMN_DROP", tableName, e.getKey(),
                        "deprecate", 1, ev("列消失 " + e.getKey(), evidence));
                diffs.add(d);
                try {
                    adapter.deprecateColumn(dsId, tableName, e.getKey(),
                            "change-detect column missing");
                } catch (Exception ex) {
                    log.warn("kbcrawler: 变更-列软删失败 {}:{}: {}", tableName, e.getKey(), ex.getMessage());
                }
            }
        }
        return diffs;
    }

    private ChangeDetectDiff newDiff(String dsId, String changeType, String table, String column,
                                     String action, int structureImpact, String evidence) {
        ChangeDetectDiff d = new ChangeDetectDiff();
        d.setDsId(dsId);
        d.setChangeType(changeType);
        d.setTargetTable(table);
        d.setTargetColumn(column);
        d.setAction(action);
        d.setStructureImpact(structureImpact);
        d.setEvidence(evidence);
        return d;
    }

    private String ev(String detail, String evidence) {
        return evidence == null || evidence.isBlank() ? detail : detail + ";release=" + evidence;
    }

    private long sumImpact(com.agenthub.ai.rcagent.kb.vo.KbStructureChangeVO vo) {
        if (vo == null) {
            return 0L;
        }
        long count = 0;
        if (vo.getImpactedTemplates() != null) {
            count += vo.getImpactedTemplates();
        }
        if (vo.getImpactedLinks() != null) {
            count += vo.getImpactedLinks();
        }
        return count;
    }

    /** 刷新快照：删当前库旧行 + 全量插入当前库（简化一致性；删除表不保留快照行，不影响其它库） */
    private void refreshSnapshots(String dsId, String space, List<TableMeta> tables) {
        snapshotMapper.delete(new QueryWrapper<KbCrawlSchemaSnapshot>()
                .eq("ds_id", dsId)
                .eq("schema_name", space));
        Date now = new Date();
        for (TableMeta t : tables) {
            KbCrawlSchemaSnapshot snap = new KbCrawlSchemaSnapshot();
            snap.setDsId(dsId);
            snap.setSchemaName(space);
            snap.setTableName(t.getTableName());
            snap.setTableType(t.getTableType());
            snap.setColumnsHash(SchemaSnapshotVO.columnsHashOf(t));
            snap.setRowCountEstimate(t.getRowCountEstimate());
            snap.setTableMetaJson(buildMetaJson(t));
            snap.setCrawledAt(now);
            snap.setCreateTime(now);
            snap.setUpdateTime(now);
            snapshotMapper.insert(snap);
        }
    }

    /** 构建快照元数据 JSON（与 KbSchemaCrawlService.buildTableMetaJson 同口径：追加 isFk/indexHash/indexes，既有键全保留） */
    private String buildMetaJson(TableMeta t) {
        List<Map<String, Object>> cols = new ArrayList<>();
        if (t.getColumns() != null) {
            for (com.agenthub.ai.dbaccess.meta.ColumnMeta c : t.getColumns()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("columnName", c.getColumnName());
                m.put("dataType", c.getDataType());
                m.put("isPk", c.getIsPrimaryKey());
                // 列级外键标记：仅用于排查展示，不参与任何 diff 判定（数据迁移第 3 节）
                m.put("isFk", c.getIsForeignKey());
                m.put("comment", c.getColumnComment());
                cols.add(m);
            }
        }
        List<Map<String, Object>> idxs = new ArrayList<>();
        if (t.getIndexes() != null) {
            for (IndexMeta idx : t.getIndexes()) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("indexName", idx.getIndexName());
                im.put("columns", idx.getColumns());
                im.put("isUnique", idx.getIsUnique());
                im.put("isPrimary", idx.getIsPrimary());
                im.put("indexType", idx.getIndexType());
                idxs.add(im);
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tableType", t.getTableType());
        meta.put("comment", t.getTableComment());
        meta.put("columns", cols);
        // 索引指纹独立存储（绝不并入 columnsHashOf，护栏 G-04）
        meta.put("indexHash", SchemaSnapshotVO.indexHashOf(t));
        meta.put("indexes", idxs);
        return JSON.toJSONString(meta);
    }

    /**
     * 从快照 {@code table_meta_json} 解析索引指纹（{@code indexHash} 键），与
     * {@code KbSchemaCrawlService.indexHashFromMetaJson} 同名同语义。
     *
     * <p>历史快照缺该键 / 解析失败 → 返回 null，按「无基线」处理（首次检测视为索引变化但 structureImpact=0）。
     * 索引指纹**唯一**由 {@link SchemaSnapshotVO#indexHashOf} 产出，本方法仅解析不重算。</p>
     */
    private String indexHashFromMetaJson(String tableMetaJson) {
        if (tableMetaJson == null || tableMetaJson.isBlank()) {
            return null;
        }
        try {
            JSONObject meta = JSON.parseObject(tableMetaJson);
            return meta == null ? null : meta.getString("indexHash");
        } catch (Exception e) {
            log.debug("kbcrawler: 快照索引指纹解析失败，按无基线处理: {}", e.getMessage());
            return null;
        }
    }

    private void refreshCursor(String dsId, String space, List<TableMeta> tables) {
        StringBuilder sb = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (TableMeta t : tables) {
            names.add(t.getTableName());
            sb.append(t.getTableName()).append(':').append(SchemaSnapshotVO.columnsHashOf(t)).append(';');
        }
        Collections.sort(names);
        // SCHEMA_FULL_HASH 按 space 维度推进，各库独立作为下一次变更检测基线
        upsertCursor(dsId, space, "SCHEMA_FULL_HASH", SchemaSnapshotVO.hashHex(sb.toString()),
                "tables=" + names.size());
    }

    /**
     * upsert 游标（增量/变更检测后推进 SCHEMA_FULL_HASH，作为下一次变更检测基线依据）。
     * 唯一键 (ds_id, cursor_key, space_name)；非空间级游标 space 传 null/blank → 统一落空串。
     */
    private void upsertCursor(String dsId, String space, String key, String value, String extra) {
        String spaceName = normalizeSpace(space);
        KbCrawlCursor exist = cursorMapper.selectOne(new QueryWrapper<KbCrawlCursor>()
                .eq("ds_id", dsId)
                .eq("cursor_key", key)
                .eq("space_name", spaceName)
                .last("LIMIT 1"));
        Date now = new Date();
        if (exist == null) {
            KbCrawlCursor cursor = new KbCrawlCursor();
            cursor.setDsId(dsId);
            cursor.setCursorKey(key);
            cursor.setSpaceName(spaceName);
            cursor.setCursorValue(value);
            cursor.setExtraInfo(extra);
            cursor.setUpdateTime(now);
            cursorMapper.insert(cursor);
        } else {
            exist.setCursorValue(value);
            exist.setExtraInfo(extra);
            exist.setUpdateTime(now);
            cursorMapper.updateById(exist);
        }
    }

    /** 归一化 space：null/空白 → 空串（唯一键去重与旧数据兼容统一用空串表示"非空间级"） */
    private String normalizeSpace(String space) {
        return (space == null || space.isBlank()) ? "" : space.trim();
    }

    private DsConfigView resolveDsConfig(String dsId) {
        try {
            DsConfigView view = dataSourceRegistry.getConfig(dsId);
            if (view == null) {
                throw new BusinessException(KbCrawlerConstant.DS_NOT_REGISTERED, "数据源不存在或未注册");
            }
            return view;
        } catch (DbAccessException e) {
            if (e.getCode() == DbAccessErrorCode.NO_PROVIDER.getCode()
                    || e.getCode() == DbAccessErrorCode.DIALECT_UNSUPPORTED.getCode()) {
                throw new BusinessException(KbCrawlerConstant.PROVIDER_DIALECT_UNAVAILABLE,
                        "目标库 Provider/方言不可用");
            }
            throw new BusinessException(KbCrawlerConstant.DS_NOT_REGISTERED, "数据源不存在或不可用");
        }
    }

    /** Provider 路由 + 采集执行统一异常映射（DbAccessException → 本模块业务异常） */
    private MetadataProvider resolveProvider(DbType dbType) {
        try {
            return providerRegistry.providerOf(dbType);
        } catch (DbAccessException e) {
            throw new BusinessException(KbCrawlerConstant.PROVIDER_DIALECT_UNAVAILABLE,
                    "目标库 Provider/方言不可用");
        }
    }
}
