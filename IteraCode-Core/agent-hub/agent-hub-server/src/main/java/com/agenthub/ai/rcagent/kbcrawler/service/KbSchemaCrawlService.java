package com.agenthub.ai.rcagent.kbcrawler.service;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.ColumnTypeSignature;
import com.agenthub.ai.dbaccess.meta.IndexMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.provider.MetadataProvider;
import com.agenthub.ai.dbaccess.provider.MetadataProviderRegistry;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.rcagent.kb.vo.KbChangeSummaryVO;
import com.agenthub.ai.rcagent.kb.vo.KbSchemaBatchVO;
import com.agenthub.ai.rcagent.kbcrawler.adapter.KbStorageReviewAdapter;
import com.agenthub.ai.rcagent.kbcrawler.config.KbCrawlerProperties;
import com.agenthub.ai.rcagent.kbcrawler.constant.CursorKey;
import com.agenthub.ai.rcagent.kbcrawler.constant.KbCrawlerConstant;
import com.agenthub.ai.rcagent.kbcrawler.constant.TaskType;
import com.agenthub.ai.rcagent.kbcrawler.entity.KbCrawlCursor;
import com.agenthub.ai.rcagent.kbcrawler.entity.KbCrawlSchemaSnapshot;
import com.agenthub.ai.rcagent.kbcrawler.mapper.KbCrawlCursorMapper;
import com.agenthub.ai.rcagent.kbcrawler.mapper.KbCrawlSchemaSnapshotMapper;
import com.agenthub.ai.rcagent.kbcrawler.model.SchemaSnapshotVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Schema 自动爬取服务（FR-001）：经 module-002 MetadataProvider 采集中立元数据，
 * 全量/增量按 last_ddl_time/columns_hash，统计采样脱敏，经 {@link KbStorageReviewAdapter} 写 module-004，
 * 并刷新 kb_crawl_schema_snapshot / kb_crawl_cursor。
 *
 * <p>降级：目标库无 Provider / 方言不可用时抛 {@code 44007}（BR-004 属代码不可达降级，与此不同）；连接失败抛 44001。</p>
 */
@Slf4j
@Service
public class KbSchemaCrawlService {

    private final DataSourceRegistry dataSourceRegistry;
    private final MetadataProviderRegistry providerRegistry;
    private final KbCrawlSchemaSnapshotMapper snapshotMapper;
    private final KbCrawlCursorMapper cursorMapper;
    private final KbStorageReviewAdapter storageReviewAdapter;
    private final KbCrawlerProperties properties;

    public KbSchemaCrawlService(DataSourceRegistry dataSourceRegistry,
                                MetadataProviderRegistry providerRegistry,
                                KbCrawlSchemaSnapshotMapper snapshotMapper,
                                KbCrawlCursorMapper cursorMapper,
                                KbStorageReviewAdapter storageReviewAdapter,
                                KbCrawlerProperties properties) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.providerRegistry = providerRegistry;
        this.snapshotMapper = snapshotMapper;
        this.cursorMapper = cursorMapper;
        this.storageReviewAdapter = storageReviewAdapter;
        this.properties = properties;
    }

    /**
     * Schema 全量采集（SCHEMA_FULL）：表+列全量写 module-004 + 快照刷新。
     *
     * @return 摘要（已写表数/列数），供任务行 summary 使用
     */
    public Map<String, Object> crawlFull(String dsId) {
        return crawlSchema(dsId, true);
    }

    /**
     * Schema 增量采集（SCHEMA_INCR）：按快照 columns_hash 过滤，仅变化表/新增表写库并刷新。
     */
    public Map<String, Object> crawlIncremental(String dsId) {
        return crawlSchema(dsId, false);
    }

    /**
     * 校验数据源注册（供任务编排统一 resolve）：失败抛 44001/44007。
     */
    public void validateDs(String dsId) {
        resolveDsConfig(dsId);
    }

    private Map<String, Object> crawlSchema(String dsId, boolean full) {
        DsConfigView config = resolveDsConfig(dsId);
        DbType dbType = DbType.parse(config.getDbType());
        if (dbType == null) {
            throw new BusinessException(KbCrawlerConstant.PROVIDER_DIALECT_UNAVAILABLE,
                    "目标库类型不支持：" + config.getDbType());
        }
        MetadataProvider provider = resolveProvider(dbType);

        // 候选库集合：spaceNames 非空按多库逐库采集；否则回落 spaceName 单库（兼容旧行为）
        List<String> spaces = resolveCrawlSpaces(config);
        if (spaces.isEmpty()) {
            throw new BusinessException(KbCrawlerConstant.DS_NOT_REGISTERED, "数据源未配置可访问库");
        }

        List<Map<String, Object>> spaceSummaries = new ArrayList<>(spaces.size());
        int totalTables = 0;
        int writtenTables = 0;
        int addedTables = 0;
        int changedTables = 0;
        int successCount = 0;
        BusinessException firstError = null;

        for (String space : spaces) {
            try {
                // 逐库 full/incr 判定：无采集基线的库本次按全量建立基线（可用开关回退为纯增量）
                boolean spaceFull = full;
                if (!full
                        && properties.getSchema() != null
                        && properties.getSchema().isAutoFullWhenNoBaseline()
                        && !hasSchemaBaseline(dsId, space)) {
                    spaceFull = true;
                    log.info("kbcrawler: space 无采集基线，本次按全量建立基线 dsId={} space={}", dsId, space);
                }
                Map<String, Object> spaceSummary = crawlSpace(dsId, dbType, provider, space, spaceFull);
                spaceSummaries.add(spaceSummary);
                totalTables += (int) spaceSummary.get("totalTables");
                writtenTables += (int) spaceSummary.get("writtenTables");
                addedTables += (int) spaceSummary.get("addedTables");
                changedTables += (int) spaceSummary.get("changedTables");
                successCount++;
            } catch (BusinessException e) {
                // 单库失败不影响其它库：记入该库 error 后继续下一库，最终由汇总判定整体成败
                if (firstError == null) {
                    firstError = e;
                }
                Map<String, Object> errSummary = new LinkedHashMap<>();
                errSummary.put("space", space);
                errSummary.put("error", e.getMessage());
                spaceSummaries.add(errSummary);
                log.warn("kbcrawler: schema采集单库失败 dsId={} space={} full={}: {}",
                        dsId, space, full, e.getMessage());
            }
        }

        // 所有库均失败 → 抛首个异常，保持原失败语义（避免任务行误报成功）
        if (successCount == 0) {
            throw firstError != null ? firstError
                    : new BusinessException(KbCrawlerConstant.DS_NOT_REGISTERED, "数据源未配置可访问库");
        }

        // 跨库累加总数字段，并附各库明细 spaceSummaries
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalTables", totalTables);
        summary.put("writtenTables", writtenTables);
        summary.put("addedTables", addedTables);
        summary.put("changedTables", changedTables);
        summary.put("spaces", spaceSummaries);
        return summary;
    }

    /**
     * 解析本次采集的候选库集合：
     * <ul>
     *   <li>{@code spaceNames} 非空 → trim/去空/去重后返回（多库边界）；</li>
     *   <li>否则回落 {@code spaceName} 单元素；blank 时返回空列表（由上层拒绝采集）。</li>
     * </ul>
     */
    private List<String> resolveCrawlSpaces(DsConfigView config) {
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
     * 单库采集：采集中立元数据 → 指纹 diff → 写 module-004 → 删列清理 → 刷新快照/游标。
     * 每个库独立刷新快照与游标（kb_crawl_schema_snapshot 唯一键含 schema_name，天然支持多库）。
     *
     * @param full 该库本次是否全量（由上层逐库 full/incr 判定得出）
     * @return 该库摘要（space/totalTables/writtenTables/addedTables/changedTables/fullSync/commentOnlyTables/deprecatedColumns）
     */
    private Map<String, Object> crawlSpace(String dsId, DbType dbType, MetadataProvider provider,
                                           String space, boolean full) {
        // 1. 采集中立元数据（includeColumns 默认开启；统计采样默认关闭避免重查询）
        //    仅采集路径显式开启外键/索引采集（其余调用方保持 MetadataRequest 默认 false，零额外查询）
        MetadataRequest request = MetadataRequest.builder()
                .dsId(dsId)
                .spaceName(space)
                .includeColumns(true)
                .includeStats(properties.getSchema() != null && properties.getSchema().isIncludeStats())
                .includeForeignKeys(true)
                .includeIndexes(true)
                .build();
        List<TableMeta> tables;
        try {
            tables = provider.listTables(request);
        } catch (DbAccessException e) {
            throw mapDbAccessError(e);
        }
        if (tables == null) {
            tables = Collections.emptyList();
        }
        log.info("kbcrawler: schema采集 dsId={} space={} full={} tables={}", dsId, space, full, tables.size());

        // 2. 组装指纹：只保留变化表（增量模式）或全部（全量模式）
        List<TableMeta> toWrite = new ArrayList<>();
        int added = 0;
        int changed = 0;
        int commentOnly = 0;
        int indexOnly = 0;
        for (TableMeta table : tables) {
            String hash = SchemaSnapshotVO.columnsHashOf(table);
            KbCrawlSchemaSnapshot old = findByUnique(dsId, space, table.getTableName());
            boolean same = old != null && hash.equals(old.getColumnsHash());
            // 列指纹未变时，进一步检测注释变化（表/列注释）：仅驱动写入，不改 changes[].changedColumns 判定
            boolean commentChanged = same && commentsChanged(old.getTableMetaJson(), table);
            // 列指纹未变时，进一步检测索引清单变化（历史快照缺 indexHash 键 → 按「无基线」视为变化）：
            // 仅驱动写入与 indexOnlyTables 计数，不改 changedTables、不触发结构变更影响分析（护栏 G-05）
            boolean indexChanged = same
                    && !Objects.equals(SchemaSnapshotVO.indexHashOf(table),
                    indexHashFromMetaJson(old.getTableMetaJson()));
            if (full || old == null || !same || commentChanged || indexChanged) {
                toWrite.add(table);
                if (old == null) {
                    added++;
                } else if (!same) {
                    changed++;
                } else if (commentChanged) {
                    // 仅注释变化：纳入写入但不计入 changedTables
                    commentOnly++;
                } else {
                    // 仅索引变化：纳入写入但不计入 changedTables、不触发结构变更影响分析
                    indexOnly++;
                }
            }
        }

        // 3. 写 module-004（全量 fullSync=true 由 module-004 deprecate 缺失表；增量 false 只 upsert）
        int deprecatedColumns = 0;
        if (!toWrite.isEmpty()) {
            KbSchemaBatchVO batch = storageReviewAdapter.saveSchemaTables(
                    dsId, dbType.getCode(), space, toWrite, full);
            // 消费批量结果的 droppedColumns 做软删清理（尽力而为，不影响该库采集成败）
            deprecatedColumns = deprecateDroppedColumns(dsId, batch, space);
        }

        // 4. 刷新快照（全量=全部 upsert；增量=只刷新写入表）
        if (full) {
            // 全量模式：快照以"当前真实库"为准，清理快照中存在但本次采集已消失的表行，
            // 避免快照漂移导致后续 CHANGE_DETECT 把早已删除的表重复 deprecate/影响分析。
            // 必须按当前 space 维度清理，防止误删其它库快照行。
            pruneMissingSnapshotRows(dsId, space, tables);
        }
        refreshSnapshots(dsId, dbType.getCode(), space, full ? tables : toWrite);
        // 5. 刷新游标（full hash / last ddl）
        refreshCursors(dsId, space, tables);

        Map<String, Object> spaceSummary = new LinkedHashMap<>();
        spaceSummary.put("space", space);
        spaceSummary.put("totalTables", tables.size());
        spaceSummary.put("writtenTables", toWrite.size());
        spaceSummary.put("addedTables", added);
        spaceSummary.put("changedTables", changed);
        // 新增统计键（仅用于任务行 remark 文本与日志，非 HTTP 契约）
        spaceSummary.put("fullSync", full);
        spaceSummary.put("commentOnlyTables", commentOnly);
        // 仅索引变化表数：与 commentOnlyTables 同构，兼容扩展（非 HTTP 契约）
        spaceSummary.put("indexOnlyTables", indexOnly);
        spaceSummary.put("deprecatedColumns", deprecatedColumns);
        return spaceSummary;
    }

    /**
     * 消费 module-004 批量结果的 {@code changes[].droppedColumns}：逐列软删（deprecated）并在确实发生状态变更时触发结构变更影响分析。
     *
     * <p>幂等：已 deprecated/定位不到 → {@code deprecateColumn} 返回 false → 跳过影响分析。
     * 单列异常仅记 WARN 并继续下一列，绝不影响该库采集成败（删列清理是"尽力而为"的收尾动作）。</p>
     *
     * @return 成功 deprecate 的列数
     */
    private int deprecateDroppedColumns(String dsId, KbSchemaBatchVO batch, String space) {
        if (batch == null || batch.getChanges() == null) {
            return 0;
        }
        int deprecated = 0;
        for (KbChangeSummaryVO summary : batch.getChanges()) {
            if (summary == null || summary.getDroppedColumns() == null) {
                continue;
            }
            String tableName = summary.getTableName();
            if (tableName == null) {
                continue;
            }
            for (String columnName : summary.getDroppedColumns()) {
                if (columnName == null) {
                    continue;
                }
                try {
                    boolean ok = storageReviewAdapter.deprecateColumn(dsId, tableName, columnName,
                            "COLUMN_DROP by schema crawl(space=" + space + ")");
                    if (ok) {
                        // 仅当确实发生 deprecated 状态变更时才触发影响分析（避免重复降级）
                        storageReviewAdapter.structureChangeImpact(dsId, tableName, List.of(columnName),
                                "COLUMN_DROP", "schema-crawl;space=" + space);
                        deprecated++;
                    }
                } catch (Exception e) {
                    log.warn("kbcrawler: 删列清理失败 dsId={} table={} column={}: {}",
                            dsId, tableName, columnName, e.getMessage());
                }
            }
        }
        return deprecated;
    }

    /**
     * 判定某库是否已有采集基线（快照行存在 AND SCHEMA_FULL_HASH 游标存在）。
     *
     * <p>复用既有 {@link #countSnapshots(String, String)} 与 {@link #getCursorValue(String, String, String)}，
     * 零新增 Mapper/SQL。两者全部具备 → 该库本次按增量；任一缺失 → 该库本次按全量。</p>
     */
    public boolean hasSchemaBaseline(String dsId, String space) {
        return countSnapshots(dsId, space) > 0
                && getCursorValue(dsId, space, CursorKey.SCHEMA_FULL_HASH.getValue()) != null;
    }

    /**
     * 比较快照 {@code table_meta_json} 中缓存的表/列注释与本次采集结果，判断注释是否变化。
     *
     * <p>解析失败/结构不符 → 视为未变化（保守，记 DEBUG）。</p>
     */
    private boolean commentsChanged(String cachedMetaJson, TableMeta current) {
        if (cachedMetaJson == null || cachedMetaJson.isBlank() || current == null) {
            return false;
        }
        try {
            JSONObject meta = JSON.parseObject(cachedMetaJson);
            if (meta == null) {
                return false;
            }
            if (!Objects.equals(normalizeComment(meta.getString("comment")),
                    normalizeComment(current.getTableComment()))) {
                return true;
            }
            JSONArray cols = meta.getJSONArray("columns");
            if (cols == null || current.getColumns() == null) {
                return false;
            }
            Map<String, String> cachedComments = new HashMap<>();
            for (int i = 0; i < cols.size(); i++) {
                JSONObject c = cols.getJSONObject(i);
                if (c == null) {
                    continue;
                }
                cachedComments.put(c.getString("columnName"), c.getString("comment"));
            }
            for (ColumnMeta c : current.getColumns()) {
                if (c.getColumnName() == null) {
                    continue;
                }
                if (cachedComments.containsKey(c.getColumnName())
                        && !Objects.equals(normalizeComment(cachedComments.get(c.getColumnName())),
                        normalizeComment(c.getColumnComment()))) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.debug("kbcrawler: 快照元数据 JSON 解析失败，按无注释变化处理: {}", e.getMessage());
            return false;
        }
    }

    /** 注释归一：null 与空白视为同一（避免 null/"" 抖动导致重复写入） */
    private String normalizeComment(String comment) {
        return comment == null ? "" : comment.trim();
    }

    /** 对数据源做采集编排前的 resolve（DsConfigView 脱敏视图） */
    private DsConfigView resolveDsConfig(String dsId) {
        try {
            DsConfigView view = dataSourceRegistry.getConfig(dsId);
            if (view == null) {
                throw new BusinessException(KbCrawlerConstant.DS_NOT_REGISTERED, "数据源不存在或未注册");
            }
            return view;
        } catch (DbAccessException e) {
            throw mapDbAccessError(e);
        }
    }

    /** 按库类型解析 Provider（NO_PROVIDER → 44007） */
    private MetadataProvider resolveProvider(DbType dbType) {
        try {
            return providerRegistry.providerOf(dbType);
        } catch (DbAccessException e) {
            throw mapDbAccessError(e);
        }
    }

    /** module-002 异常 → 本模块业务异常（message 不携带连接细节） */
    private BusinessException mapDbAccessError(DbAccessException e) {
        int code = e.getCode();
        if (code == DbAccessErrorCode.UNKNOWN_DS.getCode()
                || code == DbAccessErrorCode.DS_DISABLED.getCode()
                || code == DbAccessErrorCode.DS_NOT_READY.getCode()
                || code == DbAccessErrorCode.CONNECTION_FAILED.getCode()) {
            return new BusinessException(KbCrawlerConstant.DS_NOT_REGISTERED, "数据源不存在或不可用");
        }
        if (code == DbAccessErrorCode.NO_PROVIDER.getCode()
                || code == DbAccessErrorCode.DIALECT_UNSUPPORTED.getCode()) {
            return new BusinessException(KbCrawlerConstant.PROVIDER_DIALECT_UNAVAILABLE, "目标库 Provider/方言不可用");
        }
        return new BusinessException(KbCrawlerConstant.DS_NOT_REGISTERED, "数据源访问失败");
    }

    // ==================== 快照 ====================

    /** 刷新快照（upsert 按唯一键）；crawled_at=now */
    private void refreshSnapshots(String dsId, String dbType, String schemaName, List<TableMeta> tables) {
        Date now = new Date();
        for (TableMeta t : tables) {
            String hash = SchemaSnapshotVO.columnsHashOf(t);
            KbCrawlSchemaSnapshot exist = findByUnique(dsId, schemaName, t.getTableName());
            Date ddl = null; // module-002 Provider 不暴露 last_ddl_time，走 columns_hash 全比对
            if (exist == null) {
                KbCrawlSchemaSnapshot snap = new KbCrawlSchemaSnapshot();
                snap.setDsId(dsId);
                snap.setSchemaName(schemaName);
                snap.setTableName(t.getTableName());
                snap.setTableType(t.getTableType());
                snap.setColumnsHash(hash);
                snap.setDdlTime(ddl);
                snap.setRowCountEstimate(t.getRowCountEstimate());
                snap.setTableMetaJson(buildTableMetaJson(t));
                snap.setCrawledAt(now);
                snap.setCreateTime(now);
                snap.setUpdateTime(now);
                snapshotMapper.insert(snap);
            } else {
                exist.setColumnsHash(hash);
                exist.setDdlTime(ddl);
                exist.setRowCountEstimate(t.getRowCountEstimate());
                exist.setTableMetaJson(buildTableMetaJson(t));
                exist.setCrawledAt(now);
                exist.setUpdateTime(now);
                snapshotMapper.updateById(exist);
            }
        }
    }

    /** 构建表元数据 JSON（脱敏：列名+类型+注释摘要，无业务明细；本期追加 isFk/indexHash/indexes 键，既有键全保留） */
    private String buildTableMetaJson(TableMeta t) {
        List<Map<String, Object>> cols = new ArrayList<>();
        if (t.getColumns() != null) {
            for (ColumnMeta c : t.getColumns()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("columnName", c.getColumnName());
                m.put("dataType", c.getDataType());
                // 顺带缓存类型签名，便于后续排查（既有键保留不变）
                m.put("type", ColumnTypeSignature.signature(c));
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
        // 索引指纹独立存储在 indexHash（绝不并入 columnsHashOf，护栏 G-04）；indexes 为索引摘要（可空集合）
        meta.put("indexHash", SchemaSnapshotVO.indexHashOf(t));
        meta.put("indexes", idxs);
        return JSON.toJSONString(meta);
    }

    /**
     * 从快照 {@code table_meta_json} 解析索引指纹（{@code indexHash} 键）。
     *
     * <p>历史快照缺该键 / 解析失败 → 返回 null，按「无基线」处理（首次采集视为索引变化，但不触发任何降级）。
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

    private void refreshCursors(String dsId, String spaceName, List<TableMeta> tables) {
        // 集合哈希：表名+columnsHash 连接后 SHA（粗粒度判断用）
        StringBuilder sb = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (TableMeta t : tables) {
            names.add(t.getTableName());
            sb.append(t.getTableName()).append(':').append(SchemaSnapshotVO.columnsHashOf(t)).append(';');
        }
        Collections.sort(names);
        String hash = SchemaSnapshotVO.hashHex(sb.toString());
        // SCHEMA_FULL_HASH 按 space 维度存储，多库各自持有基线哈希互不覆盖
        upsertCursor(dsId, spaceName, "SCHEMA_FULL_HASH", hash, "tables=" + names.size());
    }

    /**
     * upsert 游标：唯一键 (ds_id, cursor_key, space_name)。
     * 非空间级游标（如 MAPPER_SRC_FINGERPRINT/RUNTIME_BATCH_ID）space 传 null/blank → 统一落空串，
     * 避免 NULL 在唯一键中不去重导致重复行。
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

    /** 读取游标值（按 space 维度；非空间级游标 space 传空串/null） */
    public String getCursorValue(String dsId, String space, String cursorKey) {
        KbCrawlCursor exist = cursorMapper.selectOne(new QueryWrapper<KbCrawlCursor>()
                .eq("ds_id", dsId)
                .eq("cursor_key", cursorKey)
                .eq("space_name", normalizeSpace(space))
                .last("LIMIT 1"));
        return exist == null ? null : exist.getCursorValue();
    }

    /** 读取非空间级游标值（等价 space=""，兼容旧调用与旧数据） */
    public String getCursorValue(String dsId, String cursorKey) {
        return getCursorValue(dsId, "", cursorKey);
    }

    /** 按唯一键查快照 */
    public KbCrawlSchemaSnapshot findByUnique(String dsId, String schemaName, String tableName) {
        return snapshotMapper.selectOne(new QueryWrapper<KbCrawlSchemaSnapshot>()
                .eq("ds_id", dsId)
                .eq(schemaName != null, "schema_name", schemaName)
                .isNull(schemaName == null, "schema_name")
                .eq("table_name", tableName)
                .last("LIMIT 1"));
    }

    /**
     * 全量采集后的快照清理：删除快照中存在但当前真实库已不存在的表行（快照漂移修正）。
     * 仅在全量模式（full=true）调用；增量模式不清除，避免误删尚未重采的表。
     *
     * <p>按 {@code schemaName} 维度清理：只处理 {@code ds_id=? AND schema_name=当前space} 的行，
     * 且 table_name 不在该 space 本次采集结果中，防止多库场景误删其它库快照。</p>
     */
    private void pruneMissingSnapshotRows(String dsId, String schemaName, List<TableMeta> currentTables) {
        List<String> currentNames = new ArrayList<>(currentTables.size());
        for (TableMeta t : currentTables) {
            currentNames.add(t.getTableName());
        }
        List<KbCrawlSchemaSnapshot> all = snapshotMapper.selectList(
                new QueryWrapper<KbCrawlSchemaSnapshot>()
                        .eq("ds_id", dsId)
                        .eq("schema_name", schemaName));
        for (KbCrawlSchemaSnapshot snap : all) {
            // 双保险：仅清理当前库行（QueryWrapper 已过滤，兜底防御跨库误删）
            if (!schemaName.equals(snap.getSchemaName())) {
                continue;
            }
            if (!currentNames.contains(snap.getTableName())) {
                snapshotMapper.deleteById(snap.getId());
            }
        }
    }

    /** 某 ds 快照行数（是否具备变更检测基线） */
    public long countSnapshots(String dsId) {
        return snapshotMapper.selectCount(new QueryWrapper<KbCrawlSchemaSnapshot>().eq("ds_id", dsId));
    }

    /** 某 ds 指定库快照行数（多库场景按 space 判断各库是否有基线） */
    public long countSnapshots(String dsId, String space) {
        return snapshotMapper.selectCount(new QueryWrapper<KbCrawlSchemaSnapshot>()
                .eq("ds_id", dsId)
                .eq("schema_name", normalizeSpace(space)));
    }

    /** 全量删除该 ds 快照（供重跑时清理，MVP 变更检测不物理删业务行） */
    public void clearSnapshots(String dsId) {
        snapshotMapper.delete(new QueryWrapper<KbCrawlSchemaSnapshot>().eq("ds_id", dsId));
    }

    /** 全量刷新快照内容引用（工具方法，供测试/排查） */
    public KbCrawlSchemaSnapshotMapper snapshotMapper() {
        return snapshotMapper;
    }
}
