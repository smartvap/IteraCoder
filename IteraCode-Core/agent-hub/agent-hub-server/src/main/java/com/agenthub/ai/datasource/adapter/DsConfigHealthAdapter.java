package com.agenthub.ai.datasource.adapter;

import com.agenthub.ai.dbaccess.dialect.DialectManager;
import com.agenthub.ai.dbaccess.diagnostic.ConnectionFailureCategory;
import com.agenthub.ai.dbaccess.diagnostic.ConnectionFailureClassifier;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import com.agenthub.ai.dbaccess.provider.MetadataProviderRegistry;
import com.agenthub.ai.dbaccess.registry.EnvPlaceholderResolver;
import com.agenthub.ai.datasource.vo.HealthCheckResultVO;
import com.alibaba.druid.pool.DruidDataSource;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 数据源健康探测适配器（brownfield 交互层，FR-BE-005）。
 *
 * <p>组合 module-002 db-access-common 公开能力实现短超时结构化探测：
 * DbType/DialectManager 方言识别、MetadataProviderRegistry Provider 就绪判断、
 * EnvPlaceholderResolver 解析 ${ENV} 占位；连接/只读/字典/白名单逐项以 Druid 只读小池验证。
 * 未就绪/方言不支持时降级 NOT_IDENTIFIED + degradation，禁止散落直调 module-002 内部 API。</p>
 *
 * <p>安全（BR-002/AGENTS.md 5.4）：password 明文只存在于方法内局部变量，不打日志、
 * 不进响应；errorMessage 一律使用脱敏固定文案，禁止拼入地址/账号/异常原文。</p>
 */
@Slf4j
@Component
public class DsConfigHealthAdapter {

    /** 探测失败项枚举（api-contract） */
    public static final String ITEM_CONNECTION = "connection";
    public static final String ITEM_ACCOUNT = "account";
    public static final String ITEM_PERMISSION = "permission";
    public static final String ITEM_DRIVER = "driver";
    public static final String ITEM_DICTIONARY = "dictionary";
    public static final String ITEM_WHITELIST = "whitelist";

    /** 视为可写的权限项（SHOW GRANTS 权限列表段内精确匹配；CREATE/ALTER 另支持前缀匹配） */
    private static final Set<String> WRITE_PRIVILEGES = Set.of(
            "ALL PRIVILEGES", "INSERT", "UPDATE", "DELETE", "DROP", "ALTER",
            "CREATE", "REFERENCES", "INDEX", "TRIGGER", "EVENT");

    private final DialectManager dialectManager;
    private final MetadataProviderRegistry providerRegistry;
    private final EnvPlaceholderResolver placeholderResolver;

    public DsConfigHealthAdapter(DialectManager dialectManager,
                                 MetadataProviderRegistry providerRegistry,
                                 EnvPlaceholderResolver placeholderResolver) {
        this.dialectManager = dialectManager;
        this.providerRegistry = providerRegistry;
        this.placeholderResolver = placeholderResolver;
    }

    /** 探测参数（password 为解析后明文，仅进程内传递） */
    @Data
    @Builder
    public static class HealthProbeParam {

        private String dsId;

        /** 库类型（DbType.code） */
        private String dbType;

        /** JDBC 连接串（可能含 ${ENV} 占位） */
        private String jdbcUrl;

        /** 驱动类名（可空，按 db_type 推断） */
        private String driverClass;

        /** 只读账号（可能含 ${ENV} 占位） */
        private String readonlyUser;

        /** 密码明文（注册配置经 ${ENV} 解析 / 测试连接 passwordForTest） */
        private String password;

        /** 白名单（可空） */
        private WhitelistRule whitelist;

        /** 可访问库集合（可空/空 → 单库旧行为，回落 spaceName/连接默认库） */
        private List<String> spaceNames;

        /** 短超时 ms */
        private int timeoutMs;
    }

    /**
     * db_type 是否受支持（在枚举内且方言/Provider 就绪且驱动可加载）。
     *
     * <p>驱动可用性探测（{@code Class.forName(默认驱动)}）用于修复 OCEANBASE_ORACLE 等无驱动时的
     * 「假就绪」：驱动缺失直接返回 false。驱动加载异常原文不透出（仅作内部判定，BR-002）。</p>
     */
    public boolean isSupportedDbType(String dbTypeCode) {
        DbType dbType = DbType.parse(dbTypeCode);
        if (dbType == null) {
            return false;
        }
        try {
            dialectManager.dialectOf(dbType);
            providerRegistry.providerOf(dbType);
        } catch (DbAccessException e) {
            return false;
        }
        return isDriverAvailable(dbType);
    }

    /**
     * 驱动是否可加载（按库类型默认驱动 {@code Class.forName}）。
     *
     * <p>失败不抛出、不打印异常原文（BR-002 脱敏），仅降级为 debug 记录库类型。</p>
     */
    private boolean isDriverAvailable(DbType dbType) {
        try {
            Class.forName(dbType.getDefaultDriverClass());
            return true;
        } catch (Throwable e) {
            log.debug("ds-health: 驱动不可用 type={}", dbType);
            return false;
        }
    }

    /**
     * 执行结构化健康探测，返回脱敏诊断结果（不落任何存储，由调用方决定留痕）。
     *
     * @param param 探测参数（password 为一次性明文，调用后即弃）
     */
    public HealthCheckResultVO probe(HealthProbeParam param) {
        long start = System.currentTimeMillis();
        HealthCheckResultVO vo = new HealthCheckResultVO();
        vo.setDsId(param.getDsId());
        vo.setConnectionOk(false);
        vo.setReadonlyOk(false);
        vo.setDictionaryOk(false);
        vo.setWhitelistOk(false);
        vo.setDialectIdentified("NOT_IDENTIFIED");
        List<String> failureItems = new ArrayList<>();

        DbType dbType = DbType.parse(param.getDbType());
        if (dbType == null) {
            failureItems.add(ITEM_DRIVER);
            vo.setCheckType("PROBE");
            return finish(vo, "PARTIAL", failureItems, "不支持的库类型（需先扩展 db-access-common Provider）", start);
        }

        // 1) 方言识别：module-002 DialectManager；不支持时降级 NOT_IDENTIFIED（不中断连接探测语义）
        String dialect = "NOT_IDENTIFIED";
        try {
            dialectManager.dialectOf(dbType);
            dialect = dbType.getCode();
        } catch (DbAccessException e) {
            failureItems.add(ITEM_DRIVER);
        }
        vo.setDialectIdentified(dialect);

        // 1.5) 驱动可用性探测：驱动不可加载时记 driver（修复 OCEANBASE_ORACLE 无驱动「假就绪」）。
        //      不提前返回，仍继续连接探测以给出 connection 明细；两者失败同属 FAIL。
        if (!isDriverAvailable(dbType)) {
            failureItems.add(ITEM_DRIVER);
        }

        // 2) 连通性探测：构建只读 Druid 小池并短超时取连接（defaultReadOnly 应用层只读兜底）
        String url;
        String user;
        String driver;
        try {
            url = placeholderResolver.resolveAll(param.getJdbcUrl());
            user = placeholderResolver.resolveAll(param.getReadonlyUser());
            // 驱动解析统一走 DbType.resolveDriverClass（显式 > URL 前缀 > 库类型默认）
            driver = placeholderResolver.resolveAll(
                    dbType.resolveDriverClass(param.getDriverClass(), param.getJdbcUrl()));
        } catch (DbAccessException e) {
            // 环境变量缺失：归入 CONFIG_UNRESOLVED，填充类别字段；日志仅记类别与耗时（不记异常原文，BR-002）
            failureItems.add(ITEM_CONNECTION);
            ConnectionFailureCategory category = ConnectionFailureCategory.CONFIG_UNRESOLVED;
            applyFailureCategory(vo, category);
            log.info("ds-health: 连接配置未就绪 category={} cost={}ms",
                    category.getCode(), System.currentTimeMillis() - start);
            return finish(vo, "FAIL", failureItems, category.toErrorMessage(), start);
        }

        DruidDataSource ds = new DruidDataSource();
        ds.setName("ds-health-probe");
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(param.getPassword());
        ds.setDriverClassName(driver);
        ds.setInitialSize(0);
        ds.setMinIdle(0);
        ds.setMaxActive(2);
        ds.setMaxWait(param.getTimeoutMs());
        ds.setValidationQuery(validationQuery(dbType));
        ds.setTestWhileIdle(true);
        ds.setTestOnBorrow(true);
        ds.setDefaultReadOnly(true);
        ds.setConnectionErrorRetryAttempts(1);
        ds.setBreakAfterAcquireFailure(false);
        try {
            ds.init();
            try (Connection conn = ds.getConnection(param.getTimeoutMs())) {
                if (conn == null || !conn.isValid(3)) {
                    throw new SQLException("invalid connection");
                }
                vo.setConnectionOk(true);
            }
        } catch (SQLException e) {
            closeQuietly(ds);
            failureItems.add(ITEM_CONNECTION);
            // 连接失败原因分类（FR-BE-001~003）：类别/建议均为脱敏枚举常量，禁止拼入驱动原始 message
            ConnectionFailureCategory category = ConnectionFailureClassifier.classify(e, dbType);
            applyFailureCategory(vo, category);
            // 日志仅记类别与耗时，不记原始异常 message（BR-002 / AGENTS.md 5.4）
            log.info("ds-health: 连接失败 category={} cost={}ms",
                    category.getCode(), System.currentTimeMillis() - start);
            return finish(vo, "FAIL", failureItems, category.toErrorMessage(), start);
        }

        // 3) 只读账号校验：MySQL 族尝试 SHOW GRANTS 探测是否可写；失败或 Oracle 族保守放行（应用层 defaultReadOnly 兜底）
        boolean readonlyOk = probeReadonly(ds, dbType);
        vo.setReadonlyOk(readonlyOk);
        if (!readonlyOk) {
            failureItems.add(ITEM_ACCOUNT);
        }

        // 4) 字典权限探测：声明可访问库集合时逐库探测（任一库不可达/无权限即失败）；
        //    未声明（空/null）回落单库旧行为（整个连接可见字典）
        List<String> dictFailedSpaces = new ArrayList<>();
        boolean dictionaryOk = probeDictionary(ds, dbType, param.getSpaceNames(), dictFailedSpaces);
        vo.setDictionaryOk(dictionaryOk);
        if (!dictionaryOk) {
            failureItems.add(ITEM_DICTIONARY);
        }

        // 5) 白名单满足检查（三模式语义）：未配置(null)/ALL/DENY_ONLY/ALLOW_ONLY+非空清单均视为满足，
        //    仅显式「ALLOW_ONLY + 空清单」视为不满足，此时才加入 ITEM_WHITELIST（与启用门槛 BR-001 一致）
        vo.setWhitelistOk(whitelistConfigured(param.getWhitelist()));
        if (!vo.getWhitelistOk()) {
            failureItems.add(ITEM_WHITELIST);
        }

        closeQuietly(ds);

        // 汇总语义（data-model 健康结果汇总）：连通失败/账号失败→FAIL；其余未满足→PARTIAL；全通过→PASS
        if (!vo.getConnectionOk() || !vo.getReadonlyOk()) {
            return finish(vo, "FAIL", failureItems, summarizeError(failureItems, dictFailedSpaces), start);
        }
        if (vo.getDialectIdentified() == null
                || "NOT_IDENTIFIED".equals(vo.getDialectIdentified())
                || !vo.getDictionaryOk()
                || !vo.getWhitelistOk()) {
            return finish(vo, "PARTIAL", failureItems, summarizeError(failureItems, dictFailedSpaces), start);
        }
        return finish(vo, "PASS", failureItems, null, start);
    }

    /**
     * 只读探测：MySQL 族经 {@code SHOW GRANTS} 判定写权限；Oracle 族经权限视图判定写权限
     * （探测失败一律保守放行，由应用层 defaultReadOnly 兜底）。
     */
    private boolean probeReadonly(DruidDataSource ds, DbType dbType) {
        if (!dbType.isMysqlFamily()) {
            return probeReadonlyOracle(ds);
        }
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("SHOW GRANTS FOR CURRENT_USER()");
             ResultSet rs = ps.executeQuery()) {
            List<String> grantRows = new ArrayList<>();
            while (rs.next()) {
                grantRows.add(rs.getString(1));
            }
            return !isWritableGrants(grantRows);
        } catch (SQLException e) {
            // 无 SHOW GRANTS 权限时无法判定写权限：应用层 defaultReadOnly 兜底，保守放行
            log.debug("ds-health: SHOW GRANTS 不可用，按只读兜底放行");
            return true;
        }
    }

    /**
     * Oracle 族只读判定：依次查询 {@code USER_SYS_PRIVS} / {@code USER_TAB_PRIVS} / {@code USER_ROLE_PRIVS}，
     * 命中 INSERT/UPDATE/DELETE/CREATE/ALTER/DROP（或 DBA/RESOURCE 角色）即视为可写，返回 {@code false}。
     *
     * <p>探测失败（权限不足/视图不可用）保守放行返回 {@code true}，由应用层 defaultReadOnly 兜底。</p>
     */
    private boolean probeReadonlyOracle(DruidDataSource ds) {
        try (Connection conn = ds.getConnection()) {
            return !oracleHasWritePrivilege(conn);
        } catch (SQLException e) {
            log.debug("ds-health: Oracle 只读权限探测失败，按只读兜底放行");
            return true;
        }
    }

    /**
     * 查询 Oracle 权限视图判定账号是否具备写权限（包可见，便于单测直接校验）。
     *
     * <p>每个视图独立容错：不可用则跳过；全部不可用视为不可判定 → 返回 false（调用方保守放行只读）。</p>
     */
    boolean oracleHasWritePrivilege(Connection conn) {
        // 1) 系统权限（USER_SYS_PRIVS）
        if (anyPrivilegeMatch(conn, "SELECT PRIVILEGE FROM USER_SYS_PRIVS")) {
            return true;
        }
        // 2) 对象权限（USER_TAB_PRIVS）
        if (anyPrivilegeMatch(conn, "SELECT PRIVILEGE FROM USER_TAB_PRIVS")) {
            return true;
        }
        // 3) 角色（USER_ROLE_PRIVS：DBA/RESOURCE 等具备写能力角色）
        try (PreparedStatement ps = conn.prepareStatement("SELECT GRANTED_ROLE FROM USER_ROLE_PRIVS")) {
            ps.setQueryTimeout(2);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (isWritableRole(rs.getString(1))) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("ds-health: USER_ROLE_PRIVS 探测不可用，跳过");
        }
        return false;
    }

    /** 逐行读取权限名并判定写权限；视图不可用/异常返回 false（跳过该来源，不误判为可写） */
    private boolean anyPrivilegeMatch(Connection conn, String sql) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(2);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (isOracleWritablePrivilege(rs.getString(1))) {
                        return true;
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("ds-health: Oracle 权限视图探测不可用，跳过");
        }
        return false;
    }

    /**
     * Oracle 权限名是否具备写能力（包可见静态，便于单测直接校验）。
     *
     * <p>INSERT/UPDATE/DELETE/ALTER/DROP/CREATE 均视为可写；{@code CREATE SESSION} 为登录基础权限，
     * 普及所有账号，不视为写权限（否则将误判所有账号非只读）。</p>
     */
    static boolean isOracleWritablePrivilege(String privilege) {
        if (privilege == null || privilege.isBlank()) {
            return false;
        }
        String p = privilege.trim().toUpperCase(Locale.ROOT);
        if ("CREATE SESSION".equals(p)) {
            return false;
        }
        return p.contains("INSERT") || p.contains("UPDATE") || p.contains("DELETE")
                || p.contains("ALTER") || p.contains("DROP") || p.contains("CREATE");
    }

    /** Oracle 角色是否具备写能力（DBA / RESOURCE；CONNECT 仅登录能力不视为可写） */
    static boolean isWritableRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String r = role.trim().toUpperCase(Locale.ROOT);
        return r.contains("DBA") || "RESOURCE".equals(r);
    }

    /**
     * 解析 {@code SHOW GRANTS} 结果行，判断账号是否具备写权限（包可见静态，便于单测直接校验）。
     *
     * <p>逐行解析，仅检查「权限列表段」（去掉行首 {@code GRANT } 前缀、截到 {@code ON } 之前），
     * 以逗号分割权限项后做精确/前缀匹配，避免库名或表名中误含 INSERT/CREATE 等子串导致误判；
     * {@code SHOW GRANTS} 每行以 {@code GRANT } 开头属语法而非权限，故不参与判定。</p>
     *
     * @param grantRows {@code SHOW GRANTS} 的原始结果行（可含 null/空行）
     * @return true 表示账号具备写权限（不可作为只读账号）；false 表示仅只读
     */
    static boolean isWritableGrants(List<String> grantRows) {
        if (grantRows == null || grantRows.isEmpty()) {
            return false;
        }
        for (String row : grantRows) {
            if (row == null || row.isBlank()) {
                continue;
            }
            String up = row.toUpperCase(Locale.ROOT);
            // 授权能力本身视为可写（WITH GRANT OPTION）
            if (up.contains("WITH GRANT OPTION")) {
                return true;
            }
            // 仅取权限列表段：去掉行首 "GRANT " 前缀，截到 " ON " 之前
            String privPart = up;
            int onIdx = up.indexOf(" ON ");
            if (onIdx > 0) {
                int start = onIdx > "GRANT ".length() ? "GRANT ".length() : 0;
                privPart = up.substring(start, onIdx);
            }
            for (String token : privPart.split(",")) {
                String t = token.trim();
                if (t.isEmpty()) {
                    continue;
                }
                if (WRITE_PRIVILEGES.contains(t) || t.startsWith("CREATE ") || t.startsWith("ALTER ")) {
                    return true; // 可写
                }
            }
        }
        return false; // 只读
    }

    /**
     * 字典权限探测（多库）：声明可访问库集合时逐库探测，任一库不可达/无权限即整体失败；
     * 集合为空/null 回落单库旧行为 {@link #probeDictionary(DruidDataSource, DbType)}（行为不变）。
     *
     * @param failedSpaces 出参：探测失败的库名（仅库名，脱敏）；全部通过时保持为空
     */
    boolean probeDictionary(DruidDataSource ds, DbType dbType, List<String> spaceNames,
                            List<String> failedSpaces) {
        List<String> spaces = normalizeSpaces(spaceNames);
        if (spaces.isEmpty()) {
            return probeDictionary(ds, dbType);
        }
        try (Connection conn = ds.getConnection()) {
            return probeDictionary(conn, dbType, spaces, failedSpaces);
        } catch (SQLException e) {
            // 取连接失败：无法区分单库，声明库全部记为失败（仅记库名，不含地址/账号/异常原文）
            failedSpaces.addAll(spaces);
            log.debug("ds-health: 多库字典探测获取连接失败 type={}", dbType);
            return false;
        }
    }

    /**
     * 多库字典探测核心（包可见，便于单测直接校验逐库执行与失败聚合）：
     * 逐库参数化探测，任一失败记入 {@code failedSpaces} 并返回 false。
     */
    boolean probeDictionary(Connection conn, DbType dbType, List<String> spaces, List<String> failedSpaces) {
        boolean allOk = true;
        for (String space : spaces) {
            if (!probeDictionaryOfSpace(conn, dbType, space)) {
                allOk = false;
                failedSpaces.add(space);
            }
        }
        return allOk;
    }

    /**
     * 单库字典权限探测（库名一律 PreparedStatement 参数化，禁止字符串拼接防注入）。
     *
     * <ul>
     *   <li>MySQL/OceanBase-MySQL：{@code information_schema.tables WHERE TABLE_SCHEMA = ?}</li>
     *   <li>Oracle/OceanBase-Oracle：{@code all_tables WHERE owner = ?}（owner 大写）；
     *       若按 owner 过滤不可用（权限/兼容模式差异）则回退原整库 {@code user_tables} 探测</li>
     * </ul>
     */
    boolean probeDictionaryOfSpace(Connection conn, DbType dbType, String space) {
        if (dbType.isOracleFamily()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM all_tables WHERE owner = ?")) {
                ps.setQueryTimeout(2);
                // Oracle 数据字典 owner 为大写；OceanBase-Oracle 同口径
                ps.setString(1, space.toUpperCase(Locale.ROOT));
                try (ResultSet ignored = ps.executeQuery()) {
                    return true;
                }
            } catch (SQLException e) {
                // owner 过滤不可靠（如兼容模式 all_tables 差异）→ 回退原整库探测并记录说明
                log.debug("ds-health: Oracle 按 owner 探测不可用，回退整库 user_tables 探测");
                return probeDictionaryWholeConn(conn, dbType);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE TABLE_SCHEMA = ?")) {
            ps.setQueryTimeout(2);
            ps.setString(1, space);
            try (ResultSet ignored = ps.executeQuery()) {
                return true;
            }
        } catch (SQLException e) {
            log.debug("ds-health: 字典权限探测失败 type={} space={}", dbType, space);
            return false;
        }
    }

    /** 规范化库集合：trim/去空/去重（保持声明顺序）；null/空返回空列表（表示单库旧行为） */
    List<String> normalizeSpaces(List<String> spaceNames) {
        if (spaceNames == null || spaceNames.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String raw : spaceNames) {
            if (raw != null && !raw.isBlank()) {
                distinct.add(raw.trim());
            }
        }
        return new ArrayList<>(distinct);
    }

    /** 字典权限探测（单库旧行为）：能查信息字典视图即通过 */
    private boolean probeDictionary(DruidDataSource ds, DbType dbType) {
        try (Connection conn = ds.getConnection()) {
            return probeDictionaryWholeConn(conn, dbType);
        } catch (SQLException e) {
            log.debug("ds-health: 字典权限探测失败 type={}", dbType);
            return false;
        }
    }

    /** 整库字典探测（不带 schema 过滤，单库兜底与 Oracle 回退共用） */
    private boolean probeDictionaryWholeConn(Connection conn, DbType dbType) {
        String sql = dbType.isOracleFamily()
                ? "SELECT COUNT(*) FROM user_tables"
                : "SELECT COUNT(*) FROM information_schema.tables";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(2);
            try (ResultSet ignored = ps.executeQuery()) {
                return true;
            }
        } catch (SQLException e) {
            log.debug("ds-health: 字典权限探测失败 type={}", dbType);
            return false;
        }
    }

    private boolean whitelistConfigured(WhitelistRule whitelist) {
        // 未配置(null)/ALL/DENY_ONLY/ALLOW_ONLY+非空清单均视为满足；仅显式 ALLOW_ONLY+空清单视为不满足
        return whitelist == null || !whitelist.isExplicitRejectAll();
    }

    /** 填充失败类别三字段（兼容新增）：类别码/类别中文/处置建议，均为脱敏枚举常量 */
    private void applyFailureCategory(HealthCheckResultVO vo, ConnectionFailureCategory category) {
        vo.setFailureCategory(category.getCode());
        vo.setFailureCategoryText(category.getText());
        vo.setFailureSuggestion(category.getSuggestion());
    }

    private HealthCheckResultVO finish(HealthCheckResultVO vo, String result,
                                       List<String> failureItems, String errorMessage,
                                       long start) {
        vo.setCheckResult(result);
        vo.setFailureItems(failureItems);
        vo.setErrorMessage(errorMessage);
        vo.setCostMs(System.currentTimeMillis() - start);
        return vo;
    }

    /** 脱敏错误汇总（仅失败项描述，不含地址/账号/异常原文） */
    private String summarizeError(List<String> failureItems) {
        if (failureItems.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("健康检查未通过：");
        for (String item : failureItems) {
            if (sb.length() > 0 && !sb.toString().endsWith("：")) {
                sb.append('/');
            }
            sb.append(item);
        }
        return sb.toString();
    }

    /**
     * 脱敏错误汇总（含多库字典探测失败库名）：附加诊断仅含库名，
     * 严禁拼入连接串/账号/密码/异常原文（BR-002 / AGENTS.md 5.4）。
     */
    String summarizeError(List<String> failureItems, List<String> dictFailedSpaces) {
        String base = summarizeError(failureItems);
        if (base == null || dictFailedSpaces == null || dictFailedSpaces.isEmpty()) {
            return base;
        }
        return base + "（库 " + String.join("/", dictFailedSpaces) + " 字典探测未通过）";
    }

    private String validationQuery(DbType dbType) {
        return dbType.isOracleFamily() ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }

    private void closeQuietly(DruidDataSource ds) {
        try {
            if (ds != null && !ds.isClosed()) {
                ds.close();
            }
        } catch (Exception ignored) {
            log.debug("ds-health: 关闭探测连接池忽略异常");
        }
    }
}
