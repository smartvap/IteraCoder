package com.agenthub.ai.dbaccess.provider;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.IndexMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.meta.RelationMeta;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.dbaccess.security.SensitiveFieldRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JdbcFallback Provider：无特定 Provider 时以标准 JDBC DatabaseMetaData 降级采集（跨库类型通用兜底）。
 *
 * <p>空间匹配策略：先按 catalog（MySQL 语义）尝试，再按 schema（Oracle 语义）尝试；
 * 目标库驱动不可用时统一 CONNECTION_FAILED，无法识别 db_type 时由注册表抛 NO_PROVIDER。</p>
 */
@Component
public class JdbcFallbackProvider extends AbstractJdbcMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(JdbcFallbackProvider.class);

    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

    public JdbcFallbackProvider(DataSourceRegistry registry, SensitiveFieldRegistry sensitiveFieldRegistry) {
        super(registry, sensitiveFieldRegistry);
    }

    @Override
    public String providerName() {
        return "fallback";
    }

    @Override
    public boolean supports(DbType dbType) {
        // 兜底 Provider 适用于任意库类型（仅当无特定 Provider 时被路由）
        return dbType != null;
    }

    @Override
    public List<TableMeta> listTables(MetadataRequest request) {
        DataSourceContext context = resolveContext(request);
        try (Connection conn = openConnection(context)) {
            DatabaseMetaData meta = conn.getMetaData();
            String space = resolveSpace(conn, context, request);
            if (space == null) {
                // 无法限定 schema（含集合外越界被拒）：拒绝返回全库元数据（防 schema 泄露）
                return new ArrayList<>();
            }
            List<TableMeta> tables = new ArrayList<>();
            // 优先 catalog 匹配（MySQL/OceanBase-MySQL），为空再按 schema 匹配（Oracle）
            List<String> names = readTables(meta, space, null);
            if (names.isEmpty()) {
                names = readTables(meta, null, space);
            }
            for (String tableName : names) {
                if (!tableAllowed(context, tableName)) {
                    continue;
                }
                tables.add(TableMeta.builder()
                        .tableName(tableName)
                        .spaceName(space)
                        .dbType(DbType.parse(context.getConfig().getDbType()))
                        .tableType("TABLE")
                        .provider(providerName())
                        .build());
            }
            if (request.includeColumns()) {
                for (TableMeta table : tables) {
                    table.setColumns(listColumns0(meta, table.getTableName(), space, context,
                            request.includeForeignKeys()));
                    // 与 MySqlProvider.attachColumns 对齐：listTables 带列时也打敏感等级，供 includeStats 脱敏样本使用
                    markSensitive(request.getDsId(), table.getTableName(), table.getColumns());
                }
            }
            // includeIndexes=true 时逐表采集索引并回填（默认关闭 → 零额外查询）
            if (request.includeIndexes()) {
                for (TableMeta table : tables) {
                    Set<String> pkColumns = request.includeColumns()
                            ? primaryKeyColumnsOf(table.getColumns())
                            : readPrimaryKeys(meta, space, table.getTableName());
                    table.setIndexes(readIndexes(meta, space, table.getTableName(), pkColumns));
                }
            }
            // includeStats=true 时受限采样：行数估计 + 敏感列脱敏样本（默认不触发任何重查询）
            fillStatsIfRequested(conn, context, request, tables);
            return tables;
        } catch (SQLException e) {
            throw connFailed(e);
        }
    }

    @Override
    public List<ColumnMeta> listColumns(MetadataRequest request, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw DbAccessException.param("tableName 不能为空");
        }
        DataSourceContext context = resolveContext(request);
        try (Connection conn = openConnection(context)) {
            String space = resolveSpace(conn, context, request);
            if (space == null) {
                // 无法限定 schema（含越界被拒）：拒绝返回（防跨库同名表列泄露）
                return new ArrayList<>();
            }
            List<ColumnMeta> columns = listColumns0(conn.getMetaData(), tableName, space, context,
                    request.includeForeignKeys());
            markSensitive(request.getDsId(), tableName, columns);
            if (request.includeStats()) {
                fillColumnSamplesIfRequested(conn, context, request, space, tableName, columns);
            }
            return columns;
        } catch (SQLException e) {
            throw connFailed(e);
        }
    }

    @Override
    public List<RelationMeta> listRelations(MetadataRequest request, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw DbAccessException.param("tableName 不能为空");
        }
        DataSourceContext context = resolveContext(request);
        try (Connection conn = openConnection(context)) {
            String space = resolveSpace(conn, context, request);
            if (space == null) {
                // 无法限定 schema（含越界被拒）：拒绝返回（防跨库关系泄露）
                return new ArrayList<>();
            }
            List<RelationMeta> relations = new ArrayList<>();
            // 外键信息优先 schema 匹配，其次 catalog
            try (ResultSet rs = conn.getMetaData().getImportedKeys(null, space, tableName)) {
                while (rs.next()) {
                    relations.add(RelationMeta.builder()
                            .sourceTable(rs.getString("FKTABLE_NAME"))
                            .sourceColumn(rs.getString("FKCOLUMN_NAME"))
                            .targetTable(rs.getString("PKTABLE_NAME"))
                            .targetColumn(rs.getString("PKCOLUMN_NAME"))
                            .relationType("FK")
                            .constraintName(rs.getString("FK_NAME"))
                            .build());
                }
            } catch (SQLException e) {
                try (ResultSet rs2 = conn.getMetaData().getImportedKeys(space, null, tableName)) {
                    while (rs2.next()) {
                        relations.add(RelationMeta.builder()
                                .sourceTable(rs2.getString("FKTABLE_NAME"))
                                .sourceColumn(rs2.getString("FKCOLUMN_NAME"))
                                .targetTable(rs2.getString("PKTABLE_NAME"))
                                .targetColumn(rs2.getString("PKCOLUMN_NAME"))
                                .relationType("FK")
                                .constraintName(rs2.getString("FK_NAME"))
                                .build());
                    }
                }
            }
            return relations;
        } catch (SQLException e) {
            throw connFailed(e);
        }
    }

    private List<String> readTables(DatabaseMetaData meta, String catalog, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = meta.getTables(catalog, schema, "%", TABLE_TYPES)) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private List<ColumnMeta> listColumns0(DatabaseMetaData meta, String tableName,
                                          String space, DataSourceContext context,
                                          boolean includeForeignKeys) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>();
        Set<String> pks = readPrimaryKeys(meta, space, tableName);
        // 外键列：仅开关开启时读取（catalog/schema 双尝试，失败降级为空集合，不阻断列采集）
        Set<String> fks = includeForeignKeys ? readForeignKeys(meta, space, tableName) : Set.of();
        try (ResultSet rs = meta.getColumns(null, space, tableName, "%")) {
            if (rs != null) {
                while (rs.next()) {
                    String colName = rs.getString("COLUMN_NAME");
                    if (!columnAllowed(context, tableName, colName)) {
                        continue;
                    }
                    columns.add(toColumnMeta(rs, tableName, pks, fks));
                }
            }
        } catch (SQLException e) {
            // catalog/schema 组合不匹配时按另一参数维度重试
            try (ResultSet rs = meta.getColumns(space, null, tableName, "%")) {
                if (rs != null) {
                    while (rs.next()) {
                        String colName = rs.getString("COLUMN_NAME");
                        if (!columnAllowed(context, tableName, colName)) {
                            continue;
                        }
                        columns.add(toColumnMeta(rs, tableName, pks, fks));
                    }
                }
            }
        }
        return columns;
    }

    /** 列元数据装配（两个 catalog/schema 分支同口径；isForeignKey 由开关产出的外键列集合回填，默认恒 0） */
    private ColumnMeta toColumnMeta(ResultSet rs, String tableName, Set<String> pks, Set<String> fks)
            throws SQLException {
        String colName = rs.getString("COLUMN_NAME");
        int size = rs.getInt("COLUMN_SIZE");
        return ColumnMeta.builder()
                .tableName(tableName)
                .columnName(colName)
                .ordinal(rs.getInt("ORDINAL_POSITION"))
                .dataType(rs.getString("TYPE_NAME"))
                .columnSize(rs.wasNull() ? null : size)
                // 数值型精度/标度：DatabaseMetaData.getColumns 的 COLUMN_SIZE/DECIMAL_DIGITS
                // （JDBC 标准等价列，无需额外 ResultSetMetaData 探测往返）；非数值型为 NULL
                .numericPrecision(nullableInt(rs, "COLUMN_SIZE"))
                .numericScale(nullableInt(rs, "DECIMAL_DIGITS"))
                .nullable(rs.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls ? 0 : 1)
                .isPrimaryKey(pks.contains(WhitelistName.normalize(colName)) ? 1 : 0)
                .isForeignKey(fks.contains(WhitelistName.normalize(colName)) ? 1 : 0)
                .columnComment(rs.getString("REMARKS"))
                .build();
    }

    /**
     * 读取表级外键列（{@code FKCOLUMN_NAME}），沿用既有 catalog/schema 双尝试模式。
     *
     * <p>驱动不支持/抛 {@code SQLException}/返回 null → 降级为空集合并记 DEBUG，不阻断列采集。</p>
     */
    private Set<String> readForeignKeys(DatabaseMetaData meta, String space, String tableName) {
        Set<String> fks = new HashSet<>();
        try (ResultSet rs = meta.getImportedKeys(null, space, tableName)) {
            if (rs != null) {
                collectForeignKeys(rs, fks);
                return fks;
            }
        } catch (SQLException e) {
            // catalog 维度不支持 → 按 schema 维度重试
        }
        try (ResultSet rs = meta.getImportedKeys(space, null, tableName)) {
            if (rs != null) {
                collectForeignKeys(rs, fks);
            }
        } catch (SQLException e) {
            // 降级：只记 space/表名，禁止打印 SQL 全文/凭据
            log.debug("dbaccess: fallback 外键采集失败降级为空集合 space={}, table={}", space, tableName);
            return new HashSet<>();
        }
        return fks;
    }

    private static void collectForeignKeys(ResultSet rs, Set<String> fks) throws SQLException {
        while (rs.next()) {
            String column = rs.getString("FKCOLUMN_NAME");
            if (column != null) {
                fks.add(WhitelistName.normalize(column));
            }
        }
    }

    /**
     * 读取表级索引（{@code getIndexInfo}，沿用既有 catalog/schema 双尝试模式）。
     *
     * <p>按 {@code INDEX_NAME} 分组、{@code ORDINAL_POSITION} 升序（驱动保证返回顺序）；
     * {@code INDEX_NAME}/{@code COLUMN_NAME} 为空的行（统计行）跳过；
     * {@code NON_UNIQUE=false → isUnique=1}；{@code isPrimary} = 该索引列集合与 PK 列集合完全一致。
     * 驱动不支持/抛 {@code SQLException}/返回 null → 跳过该表索引并记 DEBUG，不阻断。</p>
     */
    private List<IndexMeta> readIndexes(DatabaseMetaData meta, String space, String tableName,
                                        Set<String> pkColumns) {
        Map<String, IndexMeta> byName = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(null, space, tableName, false, false)) {
            if (rs != null) {
                collectIndexes(rs, byName, space, tableName, pkColumns);
                return new ArrayList<>(byName.values());
            }
        } catch (SQLException e) {
            // catalog 维度不支持 → 按 schema 维度重试
            byName.clear();
        }
        try (ResultSet rs = meta.getIndexInfo(space, null, tableName, false, false)) {
            if (rs != null) {
                collectIndexes(rs, byName, space, tableName, pkColumns);
            }
        } catch (SQLException e) {
            log.debug("dbaccess: fallback 索引采集失败跳过 space={}, table={}", space, tableName);
            return new ArrayList<>();
        }
        return new ArrayList<>(byName.values());
    }

    private static void collectIndexes(ResultSet rs, Map<String, IndexMeta> byName,
                                       String space, String tableName, Set<String> pkColumns) throws SQLException {
        while (rs.next()) {
            String indexName = rs.getString("INDEX_NAME");
            String columnName = rs.getString("COLUMN_NAME");
            if (indexName == null || columnName == null) {
                // tableIndexStatistic / 无列统计行跳过
                continue;
            }
            IndexMeta index = byName.get(indexName);
            if (index == null) {
                // NON_UNIQUE：JDBC 以整数表达（0=唯一）；NULL 保守视为非唯一
                Integer nonUnique = nullableInt(rs, "NON_UNIQUE");
                index = IndexMeta.builder()
                        .spaceName(space)
                        .tableName(tableName)
                        .indexName(indexName)
                        .columns(new ArrayList<>())
                        .isUnique(nonUnique != null && nonUnique == 0 ? 1 : 0)
                        .isPrimary(0)
                        // DatabaseMetaData 无索引类型可透传
                        .indexType(null)
                        .build();
                byName.put(indexName, index);
            }
            index.getColumns().add(columnName);
        }
        // 主键性判定：索引列集合与 PK 列集合完全一致（PK 为空时不判定）
        if (!pkColumns.isEmpty()) {
            for (IndexMeta index : byName.values()) {
                Set<String> indexColumns = new HashSet<>();
                for (String column : index.getColumns()) {
                    indexColumns.add(WhitelistName.normalize(column));
                }
                index.setIsPrimary(indexColumns.equals(pkColumns) ? 1 : 0);
            }
        }
    }

    /** 已装配列中主键列集合（归一），供 listTables 索引主键性判定复用，避免重复 getPrimaryKeys 往返 */
    private static Set<String> primaryKeyColumnsOf(List<ColumnMeta> columns) {
        Set<String> pks = new HashSet<>();
        if (columns == null) {
            return pks;
        }
        for (ColumnMeta column : columns) {
            if (column.getIsPrimaryKey() != null && column.getIsPrimaryKey() == 1
                    && column.getColumnName() != null) {
                pks.add(WhitelistName.normalize(column.getColumnName()));
            }
        }
        return pks;
    }

    /**
     * 读取可空整数列：NULL → null，0 → 0（禁止把 NULL 折断成 0）。
     *
     * <p>必须在读取后紧接 {@code rs.wasNull()} 判定，避免被后续列读取覆盖。</p>
     */
    private static Integer nullableInt(ResultSet rs, String columnLabel) throws SQLException {
        int value = rs.getInt(columnLabel);
        return rs.wasNull() ? null : value;
    }

    private Set<String> readPrimaryKeys(DatabaseMetaData meta, String schema, String tableName) throws SQLException {
        Set<String> pks = new HashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(null, schema, tableName)) {
            while (rs.next()) {
                pks.add(WhitelistName.normalize(rs.getString("COLUMN_NAME")));
            }
        } catch (SQLException e) {
            try (ResultSet rs = meta.getPrimaryKeys(schema, null, tableName)) {
                while (rs.next()) {
                    pks.add(WhitelistName.normalize(rs.getString("COLUMN_NAME")));
                }
            }
        }
        return pks;
    }

    /** 名称规范化（复用 WhitelistRule 语义） */
    private static final class WhitelistName {
        static String normalize(String name) {
            return com.agenthub.ai.dbaccess.model.WhitelistRule.normalizeTable(name);
        }
    }
}
