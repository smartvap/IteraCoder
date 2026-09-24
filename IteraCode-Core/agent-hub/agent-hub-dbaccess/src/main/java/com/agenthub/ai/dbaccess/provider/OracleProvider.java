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
 * Oracle 元数据 Provider（真实数据字典采集；ORACLE / OCEANBASE_ORACLE 归口本实现）。
 *
 * <p>采集全部走只读连接 + PreparedStatement 参数化（OWNER/表名以 {@code ?} 绑定，无字符串拼接注入面）：
 * 表 {@code ALL_TABLES + ALL_TAB_COMMENTS}、列 {@code ALL_TAB_COLUMNS + ALL_COL_COMMENTS + 主键}、
 * 关系 {@code ALL_CONSTRAINTS('R') + ALL_CONS_COLUMNS}。OWNER 与单表 tableName 统一大写归一
 * （Oracle 未加引号标识符字典均为大写，避免 SQL 解析出的小写表名静默返回空）；
 * 白名单过滤 / 敏感打标 / includeStats 采样复用基类能力；采样标识符转义使用 ANSI 双引号（{@code mysqlFamily=false}）。</p>
 */
@Component
public class OracleProvider extends AbstractJdbcMetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(OracleProvider.class);

    /** 表清单 + 表注释（同 OWNER 左连接 ALL_TAB_COMMENTS，无注释时 COMMENTS 为空） */
    private static final String SQL_TABLES =
            "SELECT t.TABLE_NAME, t.TABLE_TYPE, c.COMMENTS AS TABLE_COMMENT "
                    + "FROM ALL_TABLES t "
                    + "LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME "
                    + "WHERE t.OWNER = ? ORDER BY t.TABLE_NAME";

    /** 整 schema 列清单 + 列注释（供 listTables 带列时一次查询回填） */
    private static final String SQL_ALL_COLUMNS =
            "SELECT c.TABLE_NAME, c.COLUMN_NAME, c.COLUMN_ID, c.DATA_TYPE, c.DATA_LENGTH, c.CHAR_LENGTH, "
                    + "c.DATA_PRECISION, c.DATA_SCALE, c.NULLABLE, cc.COMMENTS AS COLUMN_COMMENT "
                    + "FROM ALL_TAB_COLUMNS c "
                    + "LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER = c.OWNER AND cc.TABLE_NAME = c.TABLE_NAME "
                    + "AND cc.COLUMN_NAME = c.COLUMN_NAME "
                    + "WHERE c.OWNER = ? ORDER BY c.TABLE_NAME, c.COLUMN_ID";

    /** 单表列清单 + 列注释 */
    private static final String SQL_ONE_TABLE_COLUMNS =
            "SELECT c.TABLE_NAME, c.COLUMN_NAME, c.COLUMN_ID, c.DATA_TYPE, c.DATA_LENGTH, c.CHAR_LENGTH, "
                    + "c.DATA_PRECISION, c.DATA_SCALE, c.NULLABLE, cc.COMMENTS AS COLUMN_COMMENT "
                    + "FROM ALL_TAB_COLUMNS c "
                    + "LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER = c.OWNER AND cc.TABLE_NAME = c.TABLE_NAME "
                    + "AND cc.COLUMN_NAME = c.COLUMN_NAME "
                    + "WHERE c.OWNER = ? AND c.TABLE_NAME = ? ORDER BY c.COLUMN_ID";

    /** 整 schema 主键列（CONSTRAINT_TYPE='P' JOIN ALL_CONS_COLUMNS） */
    private static final String SQL_ALL_PKS =
            "SELECT ac.TABLE_NAME, acc.COLUMN_NAME "
                    + "FROM ALL_CONSTRAINTS ac "
                    + "JOIN ALL_CONS_COLUMNS acc ON acc.OWNER = ac.OWNER AND acc.CONSTRAINT_NAME = ac.CONSTRAINT_NAME "
                    + "WHERE ac.CONSTRAINT_TYPE = 'P' AND ac.OWNER = ?";

    /** 单表主键列 */
    private static final String SQL_ONE_TABLE_PKS =
            "SELECT ac.TABLE_NAME, acc.COLUMN_NAME "
                    + "FROM ALL_CONSTRAINTS ac "
                    + "JOIN ALL_CONS_COLUMNS acc ON acc.OWNER = ac.OWNER AND acc.CONSTRAINT_NAME = ac.CONSTRAINT_NAME "
                    + "WHERE ac.CONSTRAINT_TYPE = 'P' AND ac.OWNER = ? AND ac.TABLE_NAME = ?";

    /** 单表外键关系（源约束 TYPE='R' 关联引用约束 R_OWNER/R_CONSTRAINT_NAME，按 POSITION 对齐源/目标列） */
    private static final String SQL_RELATIONS =
            "SELECT ac.TABLE_NAME AS source_table, acc.COLUMN_NAME AS source_column, "
                    + "rc.TABLE_NAME AS target_table, rcc.COLUMN_NAME AS target_column, "
                    + "ac.CONSTRAINT_NAME AS constraint_name "
                    + "FROM ALL_CONSTRAINTS ac "
                    + "JOIN ALL_CONS_COLUMNS acc ON acc.OWNER = ac.OWNER AND acc.CONSTRAINT_NAME = ac.CONSTRAINT_NAME "
                    + "JOIN ALL_CONSTRAINTS rc ON rc.OWNER = ac.R_OWNER AND rc.CONSTRAINT_NAME = ac.R_CONSTRAINT_NAME "
                    + "JOIN ALL_CONS_COLUMNS rcc ON rcc.OWNER = rc.OWNER AND rcc.CONSTRAINT_NAME = rc.CONSTRAINT_NAME "
                    + "AND rcc.POSITION = acc.POSITION "
                    + "WHERE ac.CONSTRAINT_TYPE = 'R' AND ac.OWNER = ? AND ac.TABLE_NAME = ? "
                    + "ORDER BY ac.CONSTRAINT_NAME, acc.POSITION";

    /** 整库外键列（CONSTRAINT_TYPE='R' JOIN ALL_CONS_COLUMNS；仅 includeForeignKeys=true 时执行） */
    private static final String SQL_ALL_FK_COLUMNS =
            "SELECT ac.TABLE_NAME, acc.COLUMN_NAME "
                    + "FROM ALL_CONSTRAINTS ac "
                    + "JOIN ALL_CONS_COLUMNS acc ON acc.OWNER = ac.OWNER AND acc.CONSTRAINT_NAME = ac.CONSTRAINT_NAME "
                    + "WHERE ac.CONSTRAINT_TYPE = 'R' AND ac.OWNER = ?";

    /**
     * 整库索引（ALL_INDEXES JOIN ALL_IND_COLUMNS，按 COLUMN_POSITION 升序保证列序；
     * LEFT JOIN 主键约束（CONSTRAINT_TYPE='P'）按「索引名 = 主键约束名」判定 isPrimary，
     * 与索引采集合并为单次往返以满足 NFR-BE-004「每库新增查询 ≤ 2」；仅 includeIndexes=true 时执行）
     */
    private static final String SQL_ALL_INDEXES =
            "SELECT i.TABLE_NAME, i.INDEX_NAME, i.UNIQUENESS, i.INDEX_TYPE, c.COLUMN_NAME, c.COLUMN_POSITION, "
                    + "pkc.CONSTRAINT_NAME AS PK_CONSTRAINT_NAME "
                    + "FROM ALL_INDEXES i "
                    + "JOIN ALL_IND_COLUMNS c ON c.INDEX_OWNER = i.OWNER AND c.INDEX_NAME = i.INDEX_NAME "
                    + "LEFT JOIN ALL_CONSTRAINTS pkc ON pkc.OWNER = i.OWNER AND pkc.CONSTRAINT_NAME = i.INDEX_NAME "
                    + "AND pkc.CONSTRAINT_TYPE = 'P' "
                    + "WHERE i.OWNER = ? ORDER BY i.TABLE_NAME, i.INDEX_NAME, c.COLUMN_POSITION";

    public OracleProvider(DataSourceRegistry registry, SensitiveFieldRegistry sensitiveFieldRegistry) {
        super(registry, sensitiveFieldRegistry);
    }

    @Override
    public String providerName() {
        return "oracle";
    }

    @Override
    public boolean supports(DbType dbType) {
        return dbType == DbType.ORACLE || dbType == DbType.OCEANBASE_ORACLE;
    }

    @Override
    public List<TableMeta> listTables(MetadataRequest request) {
        DataSourceContext context = resolveContext(request);
        try (Connection conn = openConnection(context)) {
            String space = resolveSpace(conn, context, request);
            if (space == null) {
                // 无法限定 schema（含库集合越界被拒）：拒绝返回全库元数据（防 schema 泄露）
                return new ArrayList<>();
            }
            String owner = normalizeOwner(space);
            List<TableMeta> tables = new ArrayList<>();
            for (TableMeta table : readTables(conn, context, owner)) {
                if (tableAllowed(context, table.getTableName())) {
                    tables.add(table);
                }
            }
            if (request.includeColumns()) {
                // 列装配时按 includeForeignKeys 开关决定是否读取整库外键列（默认关闭 → 零额外查询）
                attachColumns(context, conn, owner, tables, request.includeForeignKeys());
            }
            // includeIndexes=true 时整库采集索引并回填（默认关闭 → 零额外查询）
            if (request.includeIndexes()) {
                attachIndexes(conn, owner, tables);
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
            String owner = normalizeOwner(space);
            // 表名与 OWNER 同口径大写归一：Oracle 字典未加引号标识符均为大写，
            // 否则 SQL 解析出的小写表名（如 emp）会静默返回空列；
            // 归一结果贯穿主键查询、列字典查询与 includeStats 采样（采样表引用经双引号转义，
            // Oracle 双引号标识符大小写敏感，未归一会生成 "CRM"."customer" → ORA-00942 被静默吞掉）
            String normalizedTable = normalizeName(tableName);
            Set<String> pkColumns = readPrimaryKeys(conn, owner, normalizedTable);
            // 与 attachColumns 同口径：仅 includeForeignKeys=true 时整库读取外键列并回填（默认零额外查询）
            Set<String> fkColumns = request.includeForeignKeys()
                    ? readForeignKeyColumns(conn, owner).getOrDefault(normalizedTable, Set.of())
                    : Set.of();
            List<ColumnMeta> columns = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(SQL_ONE_TABLE_COLUMNS)) {
                ps.setString(1, owner);
                ps.setString(2, normalizedTable);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String colName = rs.getString("COLUMN_NAME");
                        if (!columnAllowed(context, tableName, colName)) {
                            continue;
                        }
                        columns.add(toColumnMeta(rs, tableName, pkColumns, fkColumns));
                    }
                }
            }
            markSensitive(request.getDsId(), tableName, columns);
            if (request.includeStats()) {
                // 采样表引用必须使用归一后表名：基类 tableRefSql 生成 ANSI 双引号引用，
                // Oracle 双引号标识符大小写敏感，小写表名会命中 ORA-00942 被静默吞掉导致样本缺失
                fillColumnSamplesIfRequested(conn, context, request, owner, normalizedTable, columns);
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
            String owner = normalizeOwner(space);
            List<RelationMeta> relations = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(SQL_RELATIONS)) {
                ps.setString(1, owner);
                // 表名与 OWNER 同口径大写归一（字典存储为大写），避免小写表名静默返回空关系
                ps.setString(2, normalizeName(tableName));
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

    // ------------------------------------------------------------------
    // 采集实现（全部 PreparedStatement 参数化）
    // ------------------------------------------------------------------

    private List<TableMeta> readTables(Connection conn, DataSourceContext context, String owner) throws SQLException {
        List<TableMeta> tables = new ArrayList<>();
        DbType dbType = DbType.parse(context.getConfig().getDbType());
        try (PreparedStatement ps = conn.prepareStatement(SQL_TABLES)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tables.add(TableMeta.builder()
                            .tableName(rs.getString("TABLE_NAME"))
                            .spaceName(owner)
                            .dbType(dbType)
                            .tableComment(rs.getString("TABLE_COMMENT"))
                            .tableType(rs.getString("TABLE_TYPE"))
                            .provider(providerName())
                            .build());
                }
            }
        }
        return tables;
    }

    /** 一次查询整 schema 列并回填各表（减少采集往返），同时按表白名单过滤列 */
    private void attachColumns(DataSourceContext context, Connection conn, String owner,
                               List<TableMeta> tables, boolean includeForeignKeys) throws SQLException {
        if (tables.isEmpty()) {
            return;
        }
        Map<String, TableMeta> byName = new HashMap<>();
        for (TableMeta table : tables) {
            byName.put(table.getTableName(), table);
        }
        Map<String, Set<String>> pksByTable = readPrimaryKeysByTable(conn, owner);
        // 外键列集合：表名/列名大写归一（与 OWNER/PK 同口径）；默认关闭时不查询
        Map<String, Set<String>> fkByTable = includeForeignKeys
                ? readForeignKeyColumns(conn, owner) : Map.of();
        try (PreparedStatement ps = conn.prepareStatement(SQL_ALL_COLUMNS)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    TableMeta table = byName.get(tableName);
                    if (table == null) {
                        continue;
                    }
                    String colName = rs.getString("COLUMN_NAME");
                    if (!columnAllowed(context, tableName, colName)) {
                        continue;
                    }
                    table.getColumns().add(toColumnMeta(rs, tableName,
                            pksByTable.getOrDefault(tableName, Set.of()),
                            fkByTable.getOrDefault(tableName, Set.of())));
                }
            }
        }
        for (TableMeta table : tables) {
            markSensitive(context.getConfig().getDsId(), table.getTableName(), table.getColumns());
        }
    }

    private ColumnMeta toColumnMeta(ResultSet rs, String tableName, Set<String> pkColumns, Set<String> fkColumns)
            throws SQLException {
        String colName = rs.getString("COLUMN_NAME");
        return ColumnMeta.builder()
                .tableName(tableName)
                .columnName(colName)
                .ordinal(rs.getInt("COLUMN_ID"))
                .dataType(rs.getString("DATA_TYPE"))
                .columnSize(columnSize(rs))
                // 数值型精度/标度：Oracle 数据字典 DATA_PRECISION/DATA_SCALE，非数值型为 NULL（须与 0 区分）
                .numericPrecision(nullableInt(rs, "DATA_PRECISION"))
                .numericScale(nullableInt(rs, "DATA_SCALE"))
                .nullable("Y".equalsIgnoreCase(rs.getString("NULLABLE")) ? 1 : 0)
                .isPrimaryKey(pkColumns.contains(normalizeName(colName)) ? 1 : 0)
                // 外键列集合为大写归一集合，列名同口径比较（默认空集合 → 恒 0）
                .isForeignKey(fkColumns.contains(normalizeName(colName)) ? 1 : 0)
                .columnComment(rs.getString("COLUMN_COMMENT"))
                .build();
    }

    /** 列长度优先取字符长度（CHAR_LENGTH），否则回退字节长度（DATA_LENGTH） */
    private Integer columnSize(ResultSet rs) throws SQLException {
        int charLength = rs.getInt("CHAR_LENGTH");
        if (!rs.wasNull() && charLength > 0) {
            return charLength;
        }
        int dataLength = rs.getInt("DATA_LENGTH");
        return rs.wasNull() ? null : dataLength;
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

    /** 单表主键列集合（大写归一）；tableName 为空表示查整 schema（见 SQL_ALL_PKS） */
    private Set<String> readPrimaryKeys(Connection conn, String owner, String tableName) throws SQLException {
        Set<String> pks = new HashSet<>();
        String sql = (tableName == null || tableName.isBlank()) ? SQL_ALL_PKS : SQL_ONE_TABLE_PKS;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, owner);
            if (tableName != null && !tableName.isBlank()) {
                // 表名与 OWNER 同口径大写归一（字典存储为大写）
                ps.setString(2, normalizeName(tableName));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString("COLUMN_NAME");
                    if (col != null) {
                        pks.add(normalizeName(col));
                    }
                }
            }
        }
        return pks;
    }

    /** 整 schema 主键列集合（按表分组，大写归一） */
    private Map<String, Set<String>> readPrimaryKeysByTable(Connection conn, String owner) throws SQLException {
        Map<String, Set<String>> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_ALL_PKS)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    String col = rs.getString("COLUMN_NAME");
                    if (table != null && col != null) {
                        result.computeIfAbsent(table, key -> new HashSet<>()).add(normalizeName(col));
                    }
                }
            }
        }
        return result;
    }

    /** Oracle 数据字典 OWNER/schema 大写归一（未加引号标识符字典存储为大写） */
    private static String normalizeOwner(String space) {
        return space == null ? null : space.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // 新增采集能力（外键列 / 索引），全部由 T001 开关触发，失败降级不阻断
    // ------------------------------------------------------------------

    /**
     * 整库外键列集合（key=表名大写归一，value=列名大写归一集合）。
     *
     * <p>采集失败（{@code SQLException}/驱动不支持/返回 null）降级为空集合并记 DEBUG，不阻断主元数据采集。</p>
     */
    private Map<String, Set<String>> readForeignKeyColumns(Connection conn, String owner) {
        Map<String, Set<String>> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_ALL_FK_COLUMNS)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs == null) {
                    return result;
                }
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    String column = rs.getString("COLUMN_NAME");
                    if (table == null || column == null) {
                        continue;
                    }
                    result.computeIfAbsent(normalizeName(table), key -> new HashSet<>())
                            .add(normalizeName(column));
                }
            }
        } catch (SQLException e) {
            // 降级：只记 OWNER，禁止打印 SQL 全文/凭据
            log.debug("dbaccess: Oracle 外键列采集失败降级为空集合 owner={}", owner);
            result.clear();
        }
        return result;
    }

    /**
     * 整库索引采集并回填 {@link TableMeta#getIndexes()}（按 TABLE_NAME+INDEX_NAME 分组、COLUMN_POSITION 升序）。
     *
     * <p>采集失败降级为不设置（各表保持默认空集合）并记 DEBUG，不阻断主元数据采集。</p>
     */
    private void attachIndexes(Connection conn, String owner, List<TableMeta> tables) {
        if (tables.isEmpty()) {
            return;
        }
        Map<String, Map<String, IndexMeta>> indexesByTable = readIndexes(conn, owner);
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

    /**
     * 整库索引聚合（key=表名大写归一 → indexName → IndexMeta）。
     *
     * <p>isPrimary 依据数据字典事实「索引名 = 该表主键约束名」判定，该判定已通过 SQL 内
     * {@code LEFT JOIN ALL_CONSTRAINTS('P')} 折叠进单次索引查询（不再独立发起主键约束名查询），
     * 取 {@code PK_CONSTRAINT_NAME} 是否非空即等价于「索引名 ∈ 该表主键约束名集合」。</p>
     */
    private Map<String, Map<String, IndexMeta>> readIndexes(Connection conn, String owner) {
        Map<String, Map<String, IndexMeta>> result = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(SQL_ALL_INDEXES)) {
            ps.setString(1, owner);
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
                    String tableKey = normalizeName(tableName);
                    Map<String, IndexMeta> indexes =
                            result.computeIfAbsent(tableKey, key -> new LinkedHashMap<>());
                    IndexMeta index = indexes.get(indexName);
                    if (index == null) {
                        // LEFT JOIN 命中主键约束（pk.CONSTRAINT_TYPE='P' 且约束名 = 索引名）→ PK_CONSTRAINT_NAME 非空
                        boolean primary = rs.getString("PK_CONSTRAINT_NAME") != null;
                        index = IndexMeta.builder()
                                .spaceName(owner)
                                .tableName(tableName)
                                .indexName(indexName)
                                // UNIQUENESS='UNIQUE' → 唯一
                                .isUnique("UNIQUE".equalsIgnoreCase(rs.getString("UNIQUENESS")) ? 1 : 0)
                                .isPrimary(primary ? 1 : 0)
                                // INDEX_TYPE 原样透传，不做业务枚举约束
                                .indexType(rs.getString("INDEX_TYPE"))
                                .columns(new ArrayList<>())
                                .build();
                        indexes.put(indexName, index);
                    }
                    // SQL 已按 COLUMN_POSITION 升序返回，追加顺序即列序
                    index.getColumns().add(columnName);
                }
            }
        } catch (SQLException e) {
            log.debug("dbaccess: Oracle 索引采集失败降级为空集合 owner={}", owner);
            result.clear();
        }
        return result;
    }

    /** 标识符名归一（主键列/外键列/索引名/约束名比对大小写不敏感） */
    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
    }
}
