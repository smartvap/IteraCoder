package com.agenthub.ai.dbaccess.provider;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MySQL 元数据 Provider（P0 完整 information_schema 实现；OCEANBASE_MYSQL 归口复用本实现）。
 *
 * <p>采集全部走只读连接 + PreparedStatement 参数化（schema/table 均以 ? 绑定，无字符串拼接注入面）。</p>
 */
@Component
public class MySqlProvider extends AbstractJdbcMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(MySqlProvider.class);

    /** 整库外键列（schema 参数化绑定；REFERENCED_TABLE_NAME 非空即外键列；仅 includeForeignKeys=true 时执行） */
    private static final String SQL_FK_COLUMNS =
            "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                    + "WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME IS NOT NULL";

    /** 整库索引（schema 参数化绑定；按表名/索引名/序号排序保证列序稳定；仅 includeIndexes=true 时执行） */
    private static final String SQL_INDEXES =
            "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, INDEX_TYPE "
                    + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ? "
                    + "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX";

    public MySqlProvider(DataSourceRegistry registry, SensitiveFieldRegistry sensitiveFieldRegistry) {
        super(registry, sensitiveFieldRegistry);
    }

    @Override
    public String providerName() {
        return "mysql";
    }

    @Override
    public boolean supports(DbType dbType) {
        return dbType == DbType.MYSQL || dbType == DbType.OCEANBASE_MYSQL;
    }

    @Override
    public List<TableMeta> listTables(MetadataRequest request) {
        DataSourceContext context = resolveContext(request);
        try (Connection conn = openConnection(context)) {
            String space = resolveSpace(conn, context, request);
            if (space == null) {
                // 无法限定 schema：拒绝返回全库元数据（防 schema 泄露）
                return new ArrayList<>();
            }
            List<TableMeta> tables = new ArrayList<>();
            String sql = "SELECT TABLE_NAME, TABLE_COMMENT, TABLE_TYPE FROM information_schema.TABLES "
                    + "WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, space);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        if (!tableAllowed(context, tableName)) {
                            continue;
                        }
                        tables.add(TableMeta.builder()
                                .tableName(tableName)
                                .spaceName(space)
                                .dbType(DbType.parse(context.getConfig().getDbType()))
                                .tableComment(rs.getString("TABLE_COMMENT"))
                                .tableType(rs.getString("TABLE_TYPE"))
                                .provider(providerName())
                                .build());
                    }
                }
            }
            if (request.includeColumns()) {
                // 列装配时按 includeForeignKeys 开关决定是否读取整库外键列（默认关闭 → 零额外查询）
                attachColumns(context, conn, space, tables, request.includeForeignKeys());
            }
            // includeIndexes=true 时整库一次采集索引并回填（默认关闭 → 零额外查询）
            if (request.includeIndexes()) {
                attachIndexes(conn, space, tables);
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
                return new ArrayList<>();
            }
            List<ColumnMeta> columns = new ArrayList<>();
            // 与 listTables/attachColumns 同口径：仅 includeForeignKeys=true 时整库读取外键列并回填（默认零额外查询）
            Set<String> fkColumns = request.includeForeignKeys()
                    ? readForeignKeyColumns(conn, space).getOrDefault(normalizeName(tableName), Set.of())
                    : Set.of();
            String sql = "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, "
                    + "NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE, COLUMN_KEY, COLUMN_COMMENT "
                    + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? "
                    + "ORDER BY ORDINAL_POSITION";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, space);
                ps.setString(2, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String colName = rs.getString("COLUMN_NAME");
                        if (!columnAllowed(context, tableName, colName)) {
                            continue;
                        }
                        columns.add(toColumnMeta(rs, tableName, space, fkColumns));
                    }
                }
            }
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
                return new ArrayList<>();
            }
            List<RelationMeta> relations = new ArrayList<>();
            String sql = "SELECT kcu.TABLE_NAME AS source_table, kcu.COLUMN_NAME AS source_column, "
                    + "kcu.REFERENCED_TABLE_NAME AS target_table, kcu.REFERENCED_COLUMN_NAME AS target_column, "
                    + "kcu.CONSTRAINT_NAME AS constraint_name "
                    + "FROM information_schema.KEY_COLUMN_USAGE kcu "
                    + "WHERE kcu.TABLE_SCHEMA = ? AND kcu.TABLE_NAME = ? AND kcu.REFERENCED_TABLE_NAME IS NOT NULL "
                    + "ORDER BY kcu.ORDINAL_POSITION";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, space);
                ps.setString(2, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        relations.add(RelationMeta.builder()
                                .sourceTable(rs.getString("source_table"))
                                .sourceColumn(rs.getString("source_column"))
                                .targetTable(rs.getString("target_table"))
                                .targetColumn(rs.getString("target_column"))
                                .relationType("FK")
                                .constraintName(rs.getString("constraint_name"))
                                .build());
                    }
                }
            }
            return relations;
        } catch (SQLException e) {
            throw connFailed(e);
        }
    }

    /**
     * 一次查询整 schema 列并回填各表（减少采集往返）。
     *
     * <p>{@code includeForeignKeys=true} 时先整库读取外键列集合（归一比较），装配时透传给
     * {@link #toColumnMeta} 回填 {@code isForeignKey}；false 时不发起任何额外查询。</p>
     */
    private void attachColumns(DataSourceContext context, Connection conn, String space, List<TableMeta> tables,
                               boolean includeForeignKeys) throws SQLException {
        if (tables.isEmpty()) {
            return;
        }
        // 外键列集合：表名/列名小写归一（与 listColumns 路径同口径）
        Map<String, Set<String>> fkColumnsByTable = includeForeignKeys
                ? readForeignKeyColumns(conn, space) : Map.of();
        // 列清单必须与 listColumns 完全一致：同一份元数据在 listTables 与 listColumns 两条路径下口径相同
        String sql = "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, "
                + "NUMERIC_PRECISION, NUMERIC_SCALE, IS_NULLABLE, COLUMN_KEY, COLUMN_COMMENT "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME, ORDINAL_POSITION";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, space);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String colName = rs.getString("COLUMN_NAME");
                    for (TableMeta table : tables) {
                        if (!table.getTableName().equals(tableName)) {
                            continue;
                        }
                        if (!columnAllowed(context, tableName, colName)) {
                            break;
                        }
                        table.getColumns().add(toColumnMeta(rs, tableName, space,
                                fkColumnsByTable.getOrDefault(normalizeName(tableName), Set.of())));
                        break;
                    }
                }
            }
        }
        for (TableMeta table : tables) {
            markSensitive(context.getConfig().getDsId(), table.getTableName(), table.getColumns());
        }
    }

    private ColumnMeta toColumnMeta(ResultSet rs, String tableName, String space, Set<String> fkColumns)
            throws SQLException {
        long size = rs.getLong("CHARACTER_MAXIMUM_LENGTH");
        // 外键列集合为小写归一集合，列名同口径比较（默认空集合 → 恒 0）
        String colName = rs.getString("COLUMN_NAME");
        return ColumnMeta.builder()
                .tableName(tableName)
                .columnName(colName)
                .ordinal(rs.getInt("ORDINAL_POSITION"))
                .dataType(rs.getString("DATA_TYPE"))
                // 既有语义不变：字符/二进制族取字符最大长度，数值型为 NULL（由下方独立字段表达）
                .columnSize(rs.wasNull() ? null : (int) size)
                // 数值型精度/标度：information_schema 非数值型为 NULL，必须与 0 区分（标度 0 是合法值）
                .numericPrecision(nullableInt(rs, "NUMERIC_PRECISION"))
                .numericScale(nullableInt(rs, "NUMERIC_SCALE"))
                .nullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? 1 : 0)
                .isPrimaryKey("PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY")) ? 1 : 0)
                .isForeignKey(fkColumns.contains(normalizeName(colName)) ? 1 : 0)
                .columnComment(rs.getString("COLUMN_COMMENT"))
                .build();
    }

    /**
     * 整库外键列集合（key=表名小写归一，value=列名小写归一集合）。
     *
     * <p>采集失败（{@code SQLException}/驱动不支持/返回 null）降级为空集合并记 DEBUG，不阻断主元数据采集。</p>
     */
    private Map<String, Set<String>> readForeignKeyColumns(Connection conn, String space) {
        Map<String, Set<String>> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_FK_COLUMNS)) {
            ps.setString(1, space);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs == null) {
                    return result;
                }
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String columnName = rs.getString("COLUMN_NAME");
                    if (tableName == null || columnName == null) {
                        continue;
                    }
                    result.computeIfAbsent(normalizeName(tableName), key -> new HashSet<>())
                            .add(normalizeName(columnName));
                }
            }
        } catch (SQLException e) {
            // 降级：只记 space 名，禁止打印 SQL 全文/凭据
            log.debug("dbaccess: MySQL 外键列采集失败降级为空集合 space={}", space);
            result.clear();
        }
        return result;
    }

    /**
     * 整库索引采集并回填 {@link TableMeta#getIndexes()}（按 TABLE_NAME+INDEX_NAME 分组、SEQ_IN_INDEX 升序）。
     *
     * <p>采集失败降级为不设置（各表保持默认空集合）并记 DEBUG，不阻断主元数据采集。</p>
     */
    private void attachIndexes(Connection conn, String space, List<TableMeta> tables) {
        if (tables.isEmpty()) {
            return;
        }
        Map<String, Map<String, IndexMeta>> indexesByTable = readIndexes(conn, space);
        if (indexesByTable.isEmpty()) {
            return;
        }
        for (TableMeta table : tables) {
            Map<String, IndexMeta> indexes = indexesByTable.get(normalizeName(table.getTableName()));
            if (indexes != null && !indexes.isEmpty()) {
                table.setIndexes(new ArrayList<>(indexes.values()));
            }
        }
    }

    /** 整库索引聚合（key=表名小写归一 → indexName → IndexMeta）；失败降级为空 Map */
    private Map<String, Map<String, IndexMeta>> readIndexes(Connection conn, String space) {
        Map<String, Map<String, IndexMeta>> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_INDEXES)) {
            ps.setString(1, space);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs == null) {
                    return result;
                }
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String indexName = rs.getString("INDEX_NAME");
                    String columnName = rs.getString("COLUMN_NAME");
                    if (tableName == null || indexName == null || columnName == null) {
                        continue;
                    }
                    Map<String, IndexMeta> indexes =
                            result.computeIfAbsent(normalizeName(tableName), key -> new LinkedHashMap<>());
                    IndexMeta index = indexes.get(indexName);
                    if (index == null) {
                        Integer nonUnique = nullableInt(rs, "NON_UNIQUE");
                        index = IndexMeta.builder()
                                .spaceName(space)
                                .tableName(tableName)
                                .indexName(indexName)
                                .columns(new ArrayList<>())
                                // NON_UNIQUE=0 → 唯一；NULL 视为非唯一（保守）
                                .isUnique(nonUnique != null && nonUnique == 0 ? 1 : 0)
                                .isPrimary("PRIMARY".equalsIgnoreCase(indexName) ? 1 : 0)
                                // INDEX_TYPE 原样透传，不做业务枚举约束
                                .indexType(rs.getString("INDEX_TYPE"))
                                .build();
                        indexes.put(indexName, index);
                    }
                    // SQL 已按 SEQ_IN_INDEX 升序返回，追加顺序即列序
                    index.getColumns().add(columnName);
                }
            }
        } catch (SQLException e) {
            log.debug("dbaccess: MySQL 索引采集失败降级为空集合 space={}", space);
            result.clear();
        }
        return result;
    }

    /** 标识符归一（外键列集合与表名比对统一小写口径，避免大小写差异导致漏标） */
    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
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
}
