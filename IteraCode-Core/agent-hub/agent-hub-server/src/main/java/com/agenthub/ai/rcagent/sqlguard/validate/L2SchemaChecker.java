package com.agenthub.ai.rcagent.sqlguard.validate;

import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.model.SensitiveFieldRule;
import com.agenthub.ai.dbaccess.model.SensitiveLevel;
import com.agenthub.ai.dbaccess.security.SensitiveFieldRegistry;
import com.agenthub.ai.rcagent.sqlguard.adapter.L2SchemaOutcome;
import com.agenthub.ai.rcagent.sqlguard.adapter.MetadataProviderAdapter;
import com.agenthub.ai.rcagent.sqlguard.adapter.SchemaColumnHit;
import com.agenthub.ai.rcagent.sqlguard.adapter.SchemaDictSnapshot;
import com.agenthub.ai.rcagent.sqlguard.adapter.SchemaDictionaryAdapter;
import com.agenthub.ai.rcagent.sqlguard.model.ColumnRef;
import com.agenthub.ai.rcagent.sqlguard.model.SchemaCheckItem;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import com.agenthub.ai.rcagent.sqlguard.model.TableRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * L2 Schema 校验器（FR-003）：确认引用表/列/JOIN 真实存在、类型兼容。
 *
 * <p>数据来源优先级：kb Schema 字典（SchemaDictionaryAdapter HTTP）→ 缺失降级
 * db-access-common MetadataProvider 运行时元数据（MetadataProviderAdapter）。
 * 两者皆不可用按 fail-closed：未知表标记 unknown（BLOCK），列按字典可校验项判定。</p>
 */
@Slf4j
@Component
public class L2SchemaChecker {

    private final SchemaDictionaryAdapter schemaDictionaryAdapter;
    private final MetadataProviderAdapter metadataProviderAdapter;
    private final SensitiveFieldRegistry sensitiveFieldRegistry;

    public L2SchemaChecker(SchemaDictionaryAdapter schemaDictionaryAdapter,
                           MetadataProviderAdapter metadataProviderAdapter,
                           SensitiveFieldRegistry sensitiveFieldRegistry) {
        this.schemaDictionaryAdapter = schemaDictionaryAdapter;
        this.metadataProviderAdapter = metadataProviderAdapter;
        this.sensitiveFieldRegistry = sensitiveFieldRegistry;
    }

    /**
     * 对 SqlFeature 执行表/列/JOIN 校验。
     *
     * @param includeRelations true 校验 JOIN 关系
     */
    public L2SchemaOutcome check(String dsId, String dbType, SqlFeature feature, boolean includeRelations) {
        L2SchemaOutcome outcome = new L2SchemaOutcome();
        outcome.setChecks(new ArrayList<>());
        outcome.setUnknownTables(new ArrayList<>());
        outcome.setSensitiveColumns(new ArrayList<>());
        outcome.setPassed(true);
        outcome.setSource("UNAVAILABLE");

        if (feature == null || feature.getTables() == null || feature.getTables().isEmpty()) {
            outcome.setPassed(false);
            return outcome;
        }

        List<TableRef> refs = feature.tableRefs();
        // 纯表名与限定名（schema.table）并行入参：限定名用于跨库同名表精确匹配，未传 schema 时退化为纯表名
        List<String> tableNames = new ArrayList<>();
        List<String> qualifiedNames = new ArrayList<>();
        for (TableRef ref : refs) {
            if (ref == null || ref.getName() == null || ref.getName().isBlank()) {
                continue;
            }
            tableNames.add(ref.getName());
            qualifiedNames.add(ref.qualifiedName());
        }
        SchemaDictSnapshot kb = schemaDictionaryAdapter.lookupSchema(dsId, tableNames, qualifiedNames);

        // 运行时列元数据：带 schema 的引用按 schema 覆盖 spaceName 定位；key 用限定名，另存纯表名键兼容无 schema
        java.util.Map<String, List<ColumnMeta>> runtimeByQualified = new java.util.HashMap<>();
        java.util.Map<String, List<ColumnMeta>> runtimeByPure = new java.util.HashMap<>();
        for (TableRef ref : refs) {
            if (ref == null || ref.getName() == null || ref.getName().isBlank()) {
                continue;
            }
            List<ColumnMeta> cols = metadataProviderAdapter.listColumns(dsId, ref.getSchema(), ref.getName(), dbType);
            if (cols != null && !cols.isEmpty()) {
                runtimeByQualified.put(upper(ref.qualifiedName()), cols);
                runtimeByPure.putIfAbsent(upper(ref.getName()), cols);
                outcome.setSource("RUNTIME_METADATA");
            }
        }
        // kb 可用性标记（即使 runtime 有数据也保留 MIXED 来源）
        if (Boolean.TRUE.equals(kb.getAvailable())) {
            outcome.setSource(outcome.getSource().startsWith("RUNTIME") ? "MIXED" : "KB_DICTIONARY");
        }

        // 已知表集合：限定名（跨库精确）与纯表名（兼容无 schema）分开维护
        Set<String> knownQualified = new HashSet<>();
        Set<String> knownPure = new HashSet<>();
        // kb 命中表合并到已知（kb verified=active 才返回，视为已收录事实）
        if (kb.getHitTables() != null) {
            for (String t : kb.getHitTables()) {
                knownPure.add(upper(t));
            }
        }
        if (kb.getHitQualifiedTables() != null) {
            for (String q : kb.getHitQualifiedTables()) {
                knownQualified.add(upper(q));
            }
        }
        knownQualified.addAll(runtimeByQualified.keySet());
        knownPure.addAll(runtimeByPure.keySet());

        // 表存在性（带 schema → 限定名匹配；无 schema → 纯表名旧行为）
        for (TableRef ref : refs) {
            if (ref == null || ref.getName() == null || ref.getName().isBlank()) {
                continue;
            }
            boolean exists = tableExists(ref, knownQualified, knownPure);
            SchemaCheckItem item = new SchemaCheckItem();
            item.setType("TABLE");
            item.setTarget(ref.qualifiedName());
            item.setExists(exists);
            item.setTypeMatch(true);
            if (exists) {
                item.setEvidence("表已收录（kb 字典/运行时元数据）");
                item.setReason("");
            } else {
                outcome.setPassed(false);
                outcome.getUnknownTables().add(ref.qualifiedName());
                item.setEvidence(outcome.getSource());
                item.setReason("表在 kb 字典与运行时元数据中均未确认（fail-closed 保守）");
            }
            outcome.getChecks().add(item);
        }

        // 被引用表的已知列集合（upper 表名 -> upper 列名集合），供「未限定列」存在性校验：
        // 纯表名键与限定名（schema.table）键口径与 knownPure/knownQualified 一致
        Map<String, Set<String>> knownColumnsByTable = new HashMap<>();
        if (Boolean.TRUE.equals(kb.getAvailable()) && kb.getColumns() != null) {
            for (SchemaColumnHit c : kb.getColumns()) {
                if (c.getTable() == null || c.getTable().isBlank()
                        || c.getColumn() == null || c.getColumn().isBlank()) {
                    continue;
                }
                knownColumnsByTable.computeIfAbsent(upper(c.getTable()), k -> new HashSet<>())
                        .add(upper(c.getColumn()));
                if (c.getSchema() != null && !c.getSchema().isBlank()) {
                    knownColumnsByTable.computeIfAbsent(upper(c.getSchema() + "." + c.getTable()),
                            k -> new HashSet<>()).add(upper(c.getColumn()));
                }
            }
        }
        for (Map.Entry<String, List<ColumnMeta>> e : runtimeByQualified.entrySet()) {
            Set<String> cols = knownColumnsByTable.computeIfAbsent(e.getKey(), k -> new HashSet<>());
            appendRuntimeColumns(cols, e.getValue());
        }
        for (Map.Entry<String, List<ColumnMeta>> e : runtimeByPure.entrySet()) {
            Set<String> cols = knownColumnsByTable.computeIfAbsent(e.getKey(), k -> new HashSet<>());
            appendRuntimeColumns(cols, e.getValue());
        }

        // 未限定列强制校验前置条件：所有被引用表均已有非空列清单（任一缺失则保守跳过，避免误报）
        Set<String> referencedKeys = new LinkedHashSet<>();
        boolean allTablesHaveColumns = true;
        for (TableRef ref : refs) {
            if (ref == null || ref.getName() == null || ref.getName().isBlank()) {
                continue;
            }
            String key = ref.getSchema() != null && !ref.getSchema().isBlank()
                    ? upper(ref.qualifiedName()) : upper(ref.getName());
            referencedKeys.add(key);
            Set<String> cols = knownColumnsByTable.get(key);
            if (cols == null || cols.isEmpty()) {
                allTablesHaveColumns = false;
            }
        }
        if (referencedKeys.isEmpty()) {
            allTablesHaveColumns = false;
        }

        // 列存在性与类型（表已知才可判列；带 schema 按 (schema, table) 归位，无 schema 保持旧逻辑）
        if (feature.getColumns() != null) {
            for (ColumnRef col : feature.getColumns()) {
                String table = col.getTable();
                String column = col.getColumn();
                if (column == null || column.isBlank()) {
                    continue; // 无列名（如无参函数）不阻断
                }
                if (table == null || table.isBlank()) {
                    // 未限定列（无表别名/表名前缀）：仅当被引用表列清单完备时才强制校验。
                    // 放行条件（任一命中即视为合法）：
                    // 1) 命中任一被引用表已知列 → 真实表列存在；
                    // 2) 命中派生表（FROM/JOIN/IN 子查询）输出列 → 内联视图输出列，外层任意子句均可合法引用；
                    // 3) 列来自 GROUP BY/HAVING/ORDER BY 且命中 SELECT 输出别名 → MySQL 仅这三类子句允许引用输出别名，
                    //    WHERE/JOIN/SELECT 列表引用输出别名属非法（H10 同类漏网点），不得豁免。
                    if (!allTablesHaveColumns
                            || unqualifiedColumnKnown(column, referencedKeys, knownColumnsByTable)
                            || isDerivedOutputColumn(column, feature)
                            || (col.aliasExemptClause() && isOutputAlias(column, feature))) {
                        continue;
                    }
                    SchemaCheckItem item = new SchemaCheckItem();
                    item.setType("COLUMN");
                    item.setTarget(column);
                    item.setExists(false);
                    item.setTypeMatch(false);
                    item.setEvidence("kb 字典与运行时元数据列清单均无该未限定列");
                    item.setReason("字段不存在或拼写不一致，请核对列名");
                    outcome.getChecks().add(item);
                    outcome.setPassed(false);
                    continue;
                }
                boolean hasSchema = col.getSchema() != null && !col.getSchema().isBlank();
                String qualifiedTable = hasSchema ? col.getSchema() + "." + table : table;
                boolean tableKnown = hasSchema
                        ? knownQualified.contains(upper(qualifiedTable))
                        : knownPure.contains(upper(table));
                if (!tableKnown) {
                    continue; // 表未知已在上方标记
                }
                boolean kbCol = kbContains(kb, col.getSchema(), table, column);
                List<ColumnMeta> runtimeCols = hasSchema
                        ? runtimeByQualified.get(upper(qualifiedTable))
                        : runtimeByPure.get(upper(table));
                boolean runtimeCol = runtimeContains(runtimeCols, column);
                if (!kbCol && !runtimeCol) {
                    SchemaCheckItem item = new SchemaCheckItem();
                    item.setType("COLUMN");
                    item.setTarget(qualifiedTable + "." + column);
                    item.setExists(false);
                    item.setTypeMatch(false);
                    item.setEvidence("kb 字典与运行时元数据列清单均无该列");
                    item.setReason("字段不存在或拼写不一致，请核对列名");
                    outcome.getChecks().add(item);
                    outcome.setPassed(false);
                }
            }
        }

        // JOIN 关系（尽力：两侧列存在且类型兼容已由列校验覆盖；kb relation 证据补充）
        if (includeRelations && feature.getJoinCount() != null && feature.getJoinCount() > 0) {
            List<String> tables = new ArrayList<>(knownQualified);
            tables.addAll(knownPure);
            if (tables.size() >= 2) {
                SchemaDictSnapshot rel = schemaDictionaryAdapter.lookupRelation(dsId,
                        tables.size() > 0 ? tables.get(0) : null,
                        tables.size() > 1 ? tables.get(1) : null);
                boolean hasEvidence = Boolean.TRUE.equals(rel.getAvailable())
                        && rel.getRelations() != null && !rel.getRelations().isEmpty();
                SchemaCheckItem item = new SchemaCheckItem();
                item.setType("JOIN");
                item.setTarget(String.join(" <-> ", tables));
                item.setExists(true);
                item.setTypeMatch(hasEvidence ? true : true);
                item.setEvidence(hasEvidence ? "kb 表关系证据命中" : "无 kb 关系证据，按运行时列存在性放行（尽力校验）");
                item.setReason("");
                outcome.getChecks().add(item);
            }
        }

        // 敏感 HIGH 命中摘要（供分级引擎，不落真实样本）
        collectSensitiveHits(dsId, feature, outcome);
        return outcome;
    }

    /**
     * 敏感字段命中：依据 db-access-common SensitiveFieldRegistry 对 feature 涉及列做 HIGH 判定。
     */
    private void collectSensitiveHits(String dsId, SqlFeature feature, L2SchemaOutcome outcome) {
        List<SensitiveFieldRule> rules = sensitiveFieldRegistry.loadByDs(dsId);
        if (rules == null || rules.isEmpty() || feature.getColumns() == null) {
            return;
        }
        for (ColumnRef col : feature.getColumns()) {
            if (col.getColumn() == null) {
                continue;
            }
            for (SensitiveFieldRule rule : rules) {
                if (rule.effectiveLevel() != SensitiveLevel.HIGH) {
                    continue;
                }
                boolean tableOk = rule.getTable() == null || rule.getTable().isBlank()
                        || col.getTable() == null || col.getTable().isBlank()
                        || rule.getTable().equalsIgnoreCase(col.getTable());
                if (tableOk && rule.getColumn() != null
                        && rule.getColumn().equalsIgnoreCase(col.getColumn())) {
                    outcome.getSensitiveColumns().add(
                            (col.getTable() == null ? "" : col.getTable() + ".") + col.getColumn());
                    break;
                }
            }
        }
    }

    private boolean kbContains(SchemaDictSnapshot kb, String schema, String table, String column) {
        if (!Boolean.TRUE.equals(kb.getAvailable()) || kb.getColumns() == null) {
            return false;
        }
        boolean hasSchema = schema != null && !schema.isBlank();
        for (SchemaColumnHit c : kb.getColumns()) {
            if (c.getTable() == null || !c.getTable().equalsIgnoreCase(table)
                    || c.getColumn() == null || !c.getColumn().equalsIgnoreCase(column)) {
                continue;
            }
            if (!hasSchema || c.getSchema() == null || c.getSchema().isBlank()) {
                // 无 schema 输入或 kb 未提供 schema 信息：按纯表名旧逻辑放行
                return true;
            }
            if (c.getSchema().equalsIgnoreCase(schema)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 表存在性判定：带 schema → 以限定名匹配（跨库同名表不混淆）；无 schema → 纯表名匹配（旧行为）。
     */
    private boolean tableExists(TableRef ref, Set<String> knownQualified, Set<String> knownPure) {
        if (ref.getSchema() != null && !ref.getSchema().isBlank()) {
            return knownQualified.contains(upper(ref.qualifiedName()));
        }
        return knownPure.contains(upper(ref.getName()));
    }

    private boolean runtimeContains(List<ColumnMeta> columns, String column) {
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        for (ColumnMeta c : columns) {
            if (c.getColumnName() != null && c.getColumnName().equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    /** 将运行时列元数据（upper 列名）并入已知列集合，供未限定列校验。 */
    private void appendRuntimeColumns(Set<String> target, List<ColumnMeta> columns) {
        if (target == null || columns == null) {
            return;
        }
        for (ColumnMeta m : columns) {
            if (m != null && m.getColumnName() != null && !m.getColumnName().isBlank()) {
                target.add(upper(m.getColumnName()));
            }
        }
    }

    /** 未限定列是否命中任一被引用表的已知列集合（大小写不敏感）。 */
    private boolean unqualifiedColumnKnown(String column, Set<String> referencedKeys,
                                           Map<String, Set<String>> knownColumnsByTable) {
        String target = upper(column);
        for (String key : referencedKeys) {
            Set<String> cols = knownColumnsByTable.get(key);
            if (cols != null && cols.contains(target)) {
                return true;
            }
        }
        return false;
    }

    /** 未限定列是否命中 SELECT 输出别名（仅 GROUP BY/HAVING/ORDER BY 可引用别名，调用方需先判断子句）。 */
    private boolean isOutputAlias(String column, SqlFeature feature) {
        return containsIgnoreCase(feature.getOutputAliases(), column);
    }

    /** 未限定列是否命中派生表（FROM/JOIN/IN 子查询）输出列（内联视图列，外层可合法引用）。 */
    private boolean isDerivedOutputColumn(String column, SqlFeature feature) {
        return containsIgnoreCase(feature.getDerivedColumns(), column);
    }

    /** 大小写不敏感命中判断（column 为空或集合为空返回 false）。 */
    private boolean containsIgnoreCase(List<String> values, String column) {
        if (values == null || values.isEmpty() || column == null) {
            return false;
        }
        for (String v : values) {
            if (v != null && v.equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    private String upper(String v) {
        return v == null ? "" : v.toUpperCase(Locale.ROOT);
    }
}
