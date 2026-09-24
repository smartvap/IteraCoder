package com.agenthub.ai.dbaccess.provider;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.model.SensitiveFieldRule;
import com.agenthub.ai.dbaccess.model.SensitiveLevel;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import com.agenthub.ai.dbaccess.security.SensitiveFieldRegistry;
import com.agenthub.ai.dbaccess.security.SensitiveMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Provider 公共支撑基类：连接获取、空间解析、白名单过滤、敏感等级打标、includeStats 采样。
 *
 * <p>白名单语义：未配置白名单（null）时默认放行全部表（安全由只读账号 + 敏感字段 DENY +
 * SELECT * 限制 + maxRows/maxSeconds 兜底）；显式配置后按 WhitelistRule 的 mode/通配符过滤，
 * Provider 只返回授权对象。
 * 采样语义：仅当 {@link MetadataRequest#includeStats()} 显式开启时发起受限采样（行数估计 + 单行样本），
 * 默认零额外查询；采样失败不阻断元数据采集，样本值一律经脱敏后回填，禁止原文样本外泄。</p>
 */
public abstract class AbstractJdbcMetadataProvider implements MetadataProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcMetadataProvider.class);

    protected final DataSourceRegistry registry;
    protected final SensitiveFieldRegistry sensitiveFieldRegistry;

    protected AbstractJdbcMetadataProvider(DataSourceRegistry registry,
                                           SensitiveFieldRegistry sensitiveFieldRegistry) {
        this.registry = registry;
        this.sensitiveFieldRegistry = sensitiveFieldRegistry;
    }

    /** 按 ds_id 取只读上下文（含启用/停用/就绪校验） */
    protected DataSourceContext resolveContext(MetadataRequest request) {
        return registry.resolve(request.getDsId());
    }

    /** 获取连接（失败统一 CONNECTION_FAILED，不含连接细节） */
    protected Connection openConnection(DataSourceContext context) {
        try {
            return context.getDataSource().getConnection();
        } catch (SQLException e) {
            throw new DbAccessException(DbAccessErrorCode.CONNECTION_FAILED, "数据库连接失败", e);
        }
    }

    /**
     * 解析生效空间：请求 > ds 配置 > 连接 catalog；均缺失返回 null（此时禁止采集全部 schema，防泄露）。
     *
     * <p>库边界校验：当数据源配置了可访问库集合 {@code spaceNames}（非空）时，任一路径解析出的候选空间
     * 必须在集合内；集合外空间（含显式 request.spaceName、默认 spaceName、连接 catalog）一律按越界拒绝
     * 并返回 null（防 schema 泄露）。spaceNames 为空/null 时不设边界（回落单库 spaceName，维持旧行为）。</p>
     */
    protected String resolveSpace(Connection conn, DataSourceContext context, MetadataRequest request) {
        String candidate = resolveSpaceCandidate(conn, context, request);
        if (candidate == null) {
            return null;
        }
        if (!isSpaceWithinBoundary(configOf(context), candidate)) {
            log.debug("dbaccess: 候选空间越界拒绝采集 space={}", candidate);
            return null;
        }
        return candidate;
    }

    /**
     * 候选空间回落解析（不做边界校验）：请求 &gt; ds 配置 &gt; 连接 catalog &gt; 连接 schema &gt; 库族默认。
     *
     * <p>库族默认：Oracle 族以 {@code SELECT USER FROM DUAL} 取当前用户（schema/Owner）；
     * MySQL 族以连接默认库（catalog）兜底。任一步缺失/失败继续向下回落，最终无法解析返回 null
     * （由 {@link #resolveSpace} 统一做库集合边界与越界拒绝）。</p>
     */
    private static String resolveSpaceCandidate(Connection conn, DataSourceContext context,
                                                MetadataRequest request) {
        if (request != null && request.getSpaceName() != null && !request.getSpaceName().isBlank()) {
            return request.getSpaceName().trim();
        }
        DsConfigView config = context == null ? null : context.getConfig();
        if (config != null && config.getSpaceName() != null && !config.getSpaceName().isBlank()) {
            return config.getSpaceName().trim();
        }
        // 连接默认库（MySQL 语义）
        String catalog = readConnectionValue(conn, true);
        if (catalog != null) {
            return catalog;
        }
        // 连接默认 schema（Oracle 语义）
        String schema = readConnectionValue(conn, false);
        if (schema != null) {
            return schema;
        }
        // 库族默认（Oracle：当前用户；MySQL：连接默认库已在上游尝试）
        return familyDefaultSpace(conn, config);
    }

    /** 读取连接 catalog/schema（失败或无值时返回 null，不抛出，继续回落） */
    private static String readConnectionValue(Connection conn, boolean catalog) {
        if (conn == null) {
            return null;
        }
        try {
            String value = catalog ? conn.getCatalog() : conn.getSchema();
            return (value != null && !value.isBlank()) ? value.trim() : null;
        } catch (SQLException | RuntimeException ignored) {
            // catalog/schema 获取失败按无值处理
            return null;
        }
    }

    /**
     * 库族默认空间：Oracle 族查 {@code SELECT USER FROM DUAL} 取当前用户；
     * MySQL 族无额外默认（连接默认库已在上游 catalog 回落尝试）。探测失败返回 null（防全库采集泄露）。
     */
    private static String familyDefaultSpace(Connection conn, DsConfigView config) {
        if (conn == null || config == null) {
            return null;
        }
        DbType dbType = DbType.parse(config.getDbType());
        if (dbType == null || !dbType.isOracleFamily()) {
            return null;
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT USER FROM DUAL");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String user = rs.getString(1);
                if (user != null && !user.isBlank()) {
                    return user.trim();
                }
            }
        } catch (SQLException | RuntimeException ignored) {
            log.debug("dbaccess: Oracle 族默认空间解析失败，忽略");
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 多 schema（spaceNames 可访问库集合）解析与越界校验
    // ------------------------------------------------------------------

    /**
     * 返回该数据源应采集的 schema 清单：spaceNames 非空 → 去重非空集合；否则回落 [spaceName]（可空）。
     *
     * <p>优先级：request.spaceName 非空 → 校验其 ∈ 可访问集合（spaceNames 非空时，越界返回空清单拒绝）；
     * request 未指定 → spaceNames 非空用集合，否则回落 [config.spaceName]（spaceName 为空时返回空清单）。
     * 单次语义不变：本方法只解决"库集合边界/循环候选"问题，实际采集仍由调用方按单个 space 调 listTables。</p>
     */
    protected List<String> resolveSchemaCandidates(DataSourceContext context, MetadataRequest request) {
        return resolveSchemaCandidatesCore(configOf(context), request == null ? null : request.getSpaceName());
    }

    /**
     * 核心解析（包内可见，供单测直接覆盖）：见 {@link #resolveSchemaCandidates}。
     *
     * @param config           数据源脱敏视图（含 spaceNames/spaceName）
     * @param requestSpaceName 请求显式指定 space（可空）
     * @return 应采集库清单（有序去重；越界/无可采集时返回空清单）
     */
    static List<String> resolveSchemaCandidatesCore(DsConfigView config, String requestSpaceName) {
        if (config == null) {
            return Collections.emptyList();
        }
        List<String> accessible = accessibleSpaces(config);
        if (requestSpaceName != null && !requestSpaceName.isBlank()) {
            String req = requestSpaceName.trim();
            // 集合外显式库：越界拒绝（spaceNames 为空即未设边界，放行请求任意库）
            return isSpaceAllowed(accessible, req, isOracleFamily(config))
                    ? Collections.singletonList(req)
                    : Collections.emptyList();
        }
        if (!accessible.isEmpty()) {
            return accessible;
        }
        if (config.getSpaceName() != null && !config.getSpaceName().isBlank()) {
            return Collections.singletonList(config.getSpaceName().trim());
        }
        return Collections.emptyList();
    }

    /**
     * 规范化可访问库集合：spaceNames 逐项 trim、去空、去重（保持声明顺序）；
     * 未配置（null/空列表）返回空集合，表示"未设库集合边界（不限制）"。
     */
    static List<String> accessibleSpaces(DsConfigView config) {
        if (config == null || config.getSpaceNames() == null || config.getSpaceNames().isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> spaces = new LinkedHashSet<>();
        for (String raw : config.getSpaceNames()) {
            if (raw != null && !raw.isBlank()) {
                spaces.add(raw.trim());
            }
        }
        return new ArrayList<>(spaces);
    }

    /** 空间是否在可访问边界内：可访问集合为空（未设边界）→ 放行任意；否则必须 ∈（trim 精确匹配，MySQL 族口径） */
    static boolean isSpaceAllowed(List<String> accessible, String space) {
        return isSpaceAllowed(accessible, space, false);
    }

    /**
     * 空间是否在可访问边界内（按库族选择比较口径）。
     *
     * <p>{@code oracleFamily=true} 时对两侧复用 {@link WhitelistRule#normalizeTable} 大小写归一后比较，
     * 使配置的 {@code ["crm"]} 与回落解析出的大写 OWNER {@code "CRM"} 视为同一库；该口径与执行侧
     * {@code ReadonlyQueryExecutor.checkSpaceBoundary}（同样经归一后放行）保持一致，消除「健康检查通过但采集为空」
     * 与「采集侧越界、执行侧放行」的双口径问题。{@code oracleFamily=false}（MySQL 族，Linux 下库名大小写敏感）
     * 保持原 trim 精确匹配语义不变。</p>
     */
    static boolean isSpaceAllowed(List<String> accessible, String space, boolean oracleFamily) {
        if (accessible == null || accessible.isEmpty()) {
            return true;
        }
        if (space == null) {
            return false;
        }
        if (!oracleFamily) {
            return accessible.contains(space.trim());
        }
        String normalized = WhitelistRule.normalizeTable(space);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String allowed : accessible) {
            if (normalized.equals(WhitelistRule.normalizeTable(allowed))) {
                return true;
            }
        }
        return false;
    }

    /** 候选空间是否在配置边界内（spaceNames 为空时不限制；Oracle 族大小写不敏感，MySQL 族精确匹配） */
    static boolean isSpaceWithinBoundary(DsConfigView config, String space) {
        return isSpaceAllowed(accessibleSpaces(config), space, isOracleFamily(config));
    }

    /**
     * 数据源是否属于 Oracle 方言族（决定空间边界比较口径，与 {@link #isMysqlFamily} 同源判定）。
     *
     * <p>Oracle 族（ORACLE / OCEANBASE_ORACLE）字典标识符统一大写，边界比较需大小写归一，
     * 否则小写 spaceNames 与回落出的大写 OWNER 被误判越界；MySQL 族不受影响。</p>
     */
    static boolean isOracleFamily(DsConfigView config) {
        if (config == null || config.getDbType() == null) {
            return false;
        }
        DbType dbType = DbType.parse(config.getDbType());
        return dbType != null && dbType.isOracleFamily();
    }

    /**
     * 库集合遍历便捷入口：按 {@link #resolveSchemaCandidates} 获取应采集库清单，逐库以单 space 请求调用
     * {@link #listTables(MetadataRequest)} 采集并合并，返回 TableMeta.spaceName 为各实际 schema。
     *
     * <p>边界语义：spaceNames 未配置（候选为空）时回落单次默认空间语义（不破坏旧单库行为）；
     * request.spaceName 指定集合外库时清单为空，listTables 内部亦因越界返回空，双重拒绝防泄露。
     * 本方法非 MetadataProvider SPI 契约，供本模块/强类型子类直接使用；跨模块（如 kbcrawler 任务 D）
     * 建议按 {@link #resolveSchemaCandidates} 语义在上层循环单 space 调用 listTables。</p>
     */
    public List<TableMeta> listAllSchemas(MetadataRequest request) {
        DataSourceContext context = resolveContext(request);
        List<String> spaces = resolveSchemaCandidates(context, request);
        if (spaces.isEmpty()) {
            return listTables(request);
        }
        List<TableMeta> all = new ArrayList<>();
        for (String space : spaces) {
            MetadataRequest single = MetadataRequest.builder()
                    .dsId(request.getDsId())
                    .spaceName(space)
                    .includeColumns(request.includeColumns())
                    .includeStats(request.includeStats())
                    .build();
            List<TableMeta> per = listTables(single);
            if (per != null) {
                all.addAll(per);
            }
        }
        return all;
    }

    /** context 安全取配置视图（context/config 可空，防御未知调用） */
    private static DsConfigView configOf(DataSourceContext context) {
        return context == null ? null : context.getConfig();
    }

    protected WhitelistRule whitelistOf(DataSourceContext context) {
        return context.getConfig().getWhitelist();
    }

    /** 表是否白名单放行（whitelist null → 默认放行 true；否则按 allowTable 判定） */
    protected boolean tableAllowed(DataSourceContext context, String table) {
        WhitelistRule whitelist = whitelistOf(context);
        return whitelist == null || whitelist.allowTable(table);
    }

    /** 列是否白名单放行（whitelist null → 默认放行 true；否则按 allowColumn 判定） */
    protected boolean columnAllowed(DataSourceContext context, String table, String column) {
        WhitelistRule whitelist = whitelistOf(context);
        return whitelist == null || whitelist.allowColumn(table, column);
    }

    /** 按敏感清单对列打标（未命中保持 NONE） */
    protected void markSensitive(String dsId, String tableName, List<ColumnMeta> columns) {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        List<SensitiveFieldRule> rules = sensitiveFieldRegistry.loadByDs(dsId);
        if (rules.isEmpty()) {
            return;
        }
        for (ColumnMeta column : columns) {
            for (SensitiveFieldRule rule : rules) {
                if (rule.matches(dsId, tableName, column.getColumnName())) {
                    // effectiveLevel()：level 缺省/null 一律置 HIGH（fail-closed），
                    // 禁止 ColumnMeta.sensitiveLevel 写入 null 导致后续按「不敏感」处理（问题2）
                    column.setSensitiveLevel(rule.effectiveLevel());
                    break;
                }
            }
        }
    }

    protected DbAccessException connFailed(SQLException e) {
        return new DbAccessException(DbAccessErrorCode.CONNECTION_FAILED, "数据库连接失败", e);
    }

    // ------------------------------------------------------------------
    // includeStats 采样（行数估计 + 脱敏样本），默认零额外查询
    // ------------------------------------------------------------------

    /** 采样拼接标识符合法性校验：仅允许普通标识符字符，防止系统目录特殊表名被二次拼接造成注入面 */
    private static final java.util.regex.Pattern SAFE_IDENTIFIER =
            java.util.regex.Pattern.compile("[A-Za-z0-9_$]+");

    /**
     * 标识符是否可安全用于采样 SQL 拼接（白名单授权表/列仍需二次校验，特殊名称放弃采样）。
     */
    protected boolean isSafeIdentifier(String identifier) {
        return identifier != null && !identifier.isBlank() && SAFE_IDENTIFIER.matcher(identifier).matches();
    }

    /** 按库族转义标识符（MySQL/OceanBase-MySQL 反引号，其余按 ANSI 双引号） */
    protected String quoteIdentifier(String identifier, boolean mysqlFamily) {
        return mysqlFamily
                ? "`" + identifier.replace("`", "``") + "`"
                : "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** 由 space/table 拼接表引用（space 可空，此时仅表名） */
    protected String tableRefSql(String space, String table, boolean mysqlFamily) {
        if (!isSafeIdentifier(table)) {
            return null;
        }
        String tableQ = quoteIdentifier(table, mysqlFamily);
        if (space == null || space.isBlank()) {
            return tableQ;
        }
        if (!isSafeIdentifier(space)) {
            return null;
        }
        return quoteIdentifier(space, mysqlFamily) + "." + tableQ;
    }

    /**
     * 对单表做行数估计（COUNT 受限查询）。采样失败返回 null（不阻断元数据采集）。
     *
     * <p>表引用由调用方基于白名单授权对象经 {@link #tableRefSql} 构造；查询受 Statement 超时约束。</p>
     */
    protected Long estimateRowCount(Connection conn, String tableRefSql, int timeoutSeconds) {
        String sql = "SELECT COUNT(*) FROM " + tableRefSql;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }
            }
        } catch (SQLException e) {
            log.debug("dbaccess: 采样 rowCount 失败跳过 tableRef={}", tableRefSql);
        }
        return null;
    }

    /**
     * 读取表内任意一行的列样本（以 setMaxRows(1) 控制驱动层单行，跨库不依赖 LIMIT 方言）。
     *
     * @return 列值数组；无数据/失败返回 null，不阻断元数据采集
     */
    protected List<Object> sampleFirstRow(Connection conn, String fromClauseSql,
                                          List<String> columnRefSqls, int timeoutSeconds) {
        String sql = "SELECT " + String.join(", ", columnRefSqls) + " FROM " + fromClauseSql;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(timeoutSeconds);
            ps.setMaxRows(1);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                List<Object> values = new ArrayList<>(columnRefSqls.size());
                for (int i = 0; i < columnRefSqls.size(); i++) {
                    values.add(rs.getObject(i + 1));
                }
                return values;
            }
        } catch (SQLException e) {
            log.debug("dbaccess: 采样单行失败跳过 from={}", fromClauseSql);
            return null;
        }
    }

    /**
     * 样本展示脱敏：敏感等级命中 → 按等级脱敏；NONE/未知 → {@link SensitiveMasker#maskSample}
     * 兜底（已知强敏感模式部分脱敏、未知格式全掩码），任何路径禁止真实样本原样回填（问题2）。
     */
    protected String maskSample(Object raw, SensitiveLevel level) {
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw);
        if (level != null && level != SensitiveLevel.NONE) {
            return sensitiveFieldRegistry.mask(value, level);
        }
        return SensitiveMasker.maskSample(value);
    }

    /**
     * 采样数据源允许的最大秒数：context.limits.maxSeconds（叠加后必有默认值），缺失回退 0 表示不超时。
     */
    protected int sampleTimeoutSeconds(DataSourceContext context) {
        if (context != null && context.getLimits() != null && context.getLimits().getMaxSeconds() != null) {
            return context.getLimits().getMaxSeconds();
        }
        return 0;
    }

    /**
     * includeStats 采样入口：对已白名单过滤的 TableMeta 列表回填 rowCountEstimate 与 ColumnMeta.sampleValue。
     * 调用方保证 tables 中列已 markSensitive（敏感等级用于样本脱敏策略）。
     *
     * <p>默认不发起任何额外查询；仅当 request.includeStats() 时逐表执行 1 次 COUNT + 1 次单行采样。</p>
     */
    protected void fillStatsIfRequested(Connection conn, DataSourceContext context,
                                        MetadataRequest request, List<TableMeta> tables) {
        if (!request.includeStats() || tables == null || tables.isEmpty()) {
            return;
        }
        int timeout = sampleTimeoutSeconds(context);
        boolean mysqlFamily = isMysqlFamily(context);
        for (TableMeta table : tables) {
            String tableRef = tableRefSql(table.getSpaceName(), table.getTableName(), mysqlFamily);
            if (tableRef == null) {
                continue;
            }
            Long rowCount = estimateRowCount(conn, tableRef, timeout);
            table.setRowCountEstimate(rowCount);
            if (table.getColumns() != null && !table.getColumns().isEmpty()) {
                fillColumnSamples(conn, tableRef, mysqlFamily, timeout, table.getColumns());
            }
        }
    }

    /**
     * includeStats 单表列采样入口（listColumns 场景）：仅对敏感列回填脱敏样本，不额外做 COUNT。
     * 调用方保证 columns 已 markSensitive。
     */
    protected void fillColumnSamplesIfRequested(Connection conn, DataSourceContext context,
                                                MetadataRequest request, String space, String table,
                                                List<ColumnMeta> columns) {
        if (!request.includeStats() || columns == null || columns.isEmpty()) {
            return;
        }
        int timeout = sampleTimeoutSeconds(context);
        boolean mysqlFamily = isMysqlFamily(context);
        String tableRef = tableRefSql(space, table, mysqlFamily);
        if (tableRef == null) {
            return;
        }
        fillColumnSamples(conn, tableRef, mysqlFamily, timeout, columns);
    }

    /** 数据源是否为 MySQL 方言族（决定采样标识符转义风格） */
    protected boolean isMysqlFamily(DataSourceContext context) {
        if (context == null || context.getConfig() == null || context.getConfig().getDbType() == null) {
            return false;
        }
        com.agenthub.ai.dbaccess.model.DbType dbType =
                com.agenthub.ai.dbaccess.model.DbType.parse(context.getConfig().getDbType());
        return dbType != null && dbType.isMysqlFamily();
    }

    private void fillColumnSamples(Connection conn, String tableRef, boolean mysqlFamily,
                                   int timeout, List<ColumnMeta> columns) {
        // 仅对敏感命中列采样并脱敏，普通列不返回真实业务样本（sampleValue 可空，禁止未脱敏原文回填）
        List<ColumnMeta> sensitive = new ArrayList<>();
        for (ColumnMeta col : columns) {
            if (col.getSensitiveLevel() != null && col.getSensitiveLevel() != SensitiveLevel.NONE) {
                sensitive.add(col);
            }
        }
        if (sensitive.isEmpty()) {
            return;
        }
        List<String> refs = new ArrayList<>(sensitive.size());
        for (ColumnMeta col : sensitive) {
            if (!isSafeIdentifier(col.getColumnName())) {
                return;
            }
            refs.add(quoteIdentifier(col.getColumnName(), mysqlFamily));
        }
        List<Object> row = sampleFirstRow(conn, tableRef, refs, timeout);
        if (row == null) {
            return;
        }
        for (int i = 0; i < sensitive.size() && i < row.size(); i++) {
            Object v = row.get(i);
            if (v != null) {
                sensitive.get(i).setSampleValue(maskSample(v, sensitive.get(i).getSensitiveLevel()));
            }
        }
    }
}
