package com.agenthub.ai.dbaccess.registry;

import com.agenthub.ai.dbaccess.config.DbAccessProperties;
import com.agenthub.ai.dbaccess.constant.DbAccessConstants;
import com.agenthub.ai.dbaccess.dialect.DialectManager;
import com.agenthub.ai.dbaccess.dialect.SqlDialect;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DataSourceContext;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfig;
import com.agenthub.ai.dbaccess.model.DsConfigSpec;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.model.DsStatus;
import com.agenthub.ai.dbaccess.model.ResourceLimits;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import com.agenthub.ai.dbaccess.store.DsConfigStore;
import com.alibaba.druid.pool.DruidDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据源注册表与路由（api-contract DataSourceRegistry）。
 *
 * <p>ds_id 是唯一路由维度；连接池按 ds_id 独立并缓存，resolve 命中已构建池 P90 &lt; 10ms、缓存后 &lt; 2ms。
 * 单数据源失败快速失败，不阻塞其他 ds_id 访问。</p>
 */
@Service
public class DataSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRegistry.class);

    private final DsConfigStore configStore;
    private final ReadonlyDataSourceFactory dataSourceFactory;
    private final DialectManager dialectManager;
    private final DbAccessProperties properties;
    private final ConcurrentMap<String, DataSourceContext> contextCache = new ConcurrentHashMap<>();

    public DataSourceRegistry(DsConfigStore configStore,
                              ReadonlyDataSourceFactory dataSourceFactory,
                              DialectManager dialectManager,
                              DbAccessProperties properties) {
        this.configStore = configStore;
        this.dataSourceFactory = dataSourceFactory;
        this.dialectManager = dialectManager;
        this.properties = properties;
    }

    /**
     * 按 ds_id 返回只读数据源上下文（连接池+方言+限制）。
     *
     * @throws DbAccessException UNKNOWN_DS / DS_DISABLED / DS_NOT_READY / CONNECTION_FAILED
     */
    public DataSourceContext resolve(String dsId) {
        validateDsId(dsId);
        return contextCache.computeIfAbsent(dsId, this::buildContext);
    }

    /**
     * 返回脱敏后的 ds_config 视图（不含密码），供上层展示/白名单检查。
     */
    public DsConfigView getConfig(String dsId) {
        validateDsId(dsId);
        DsConfig config = configStore.findByDsId(dsId);
        if (config == null) {
            throw new DbAccessException(DbAccessErrorCode.UNKNOWN_DS, "未知数据源");
        }
        return config.toView();
    }

    /**
     * 列出所有启用数据源（脱敏视图），供管理台/审计列表。
     */
    public List<DsConfigView> listConfigs() {
        return configStore.listEnabled().stream()
                .map(DsConfig::toView)
                .collect(Collectors.toList());
    }

    /**
     * 注册/覆盖数据源（供 module-009 调用）：注册校验 + 预构建只读连接池 + 幂等落存储。
     *
     * @return dsId
     */
    public String register(DsConfigSpec spec) {
        if (spec == null) {
            throw DbAccessException.param("注册参数不能为空");
        }
        validateRegisterSpec(spec);
        DsConfig config = spec.toConfig();
        // 驱动解析统一走 DbType.resolveDriverClass（显式 > URL 前缀 > 库类型默认，URL 前缀优先保护存量 jdbc:mysql）
        config.setDriverClass(config.getDbType().resolveDriverClass(config.getDriverClass(), config.getJdbcUrl()));
        // 预构建只读连接池并健康检查（失败抛 CONNECTION_FAILED/DS_NOT_READY，不影响其他 ds）
        DruidDataSource probe = null;
        try {
            probe = dataSourceFactory.create(config);
        } finally {
            if (probe != null && !probe.isClosed()) {
                probe.close();
            }
        }
        DsConfig saved = configStore.save(config);
        invalidate(saved.getDsId());
        log.info("dbaccess: 注册数据源[{}]成功（幂等覆盖）", saved.getDsId());
        return saved.getDsId();
    }

    /**
     * 使指定 ds_id 的运行时上下文失效（下次 resolve 重建；关闭旧池避免泄漏）。
     */
    public void invalidate(String dsId) {
        DataSourceContext removed = contextCache.remove(dsId);
        if (removed != null && removed.getDataSource() instanceof DruidDataSource pool) {
            try {
                if (!pool.isClosed()) {
                    pool.close();
                }
            } catch (Exception ignored) {
                log.debug("dbaccess: 关闭失效连接池忽略异常 dsId={}", dsId);
            }
        }
    }

    private DataSourceContext buildContext(String dsId) {
        DsConfig config = configStore.findByDsId(dsId);
        if (config == null) {
            throw new DbAccessException(DbAccessErrorCode.UNKNOWN_DS, "未知数据源");
        }
        if (!config.enabled()) {
            throw new DbAccessException(DbAccessErrorCode.DS_DISABLED, "数据源已停用");
        }
        DruidDataSource pool = dataSourceFactory.create(config);
        SqlDialect dialect = dialectManager.dialectOf(config.getDbType());
        ResourceLimits defaultLimits = properties.defaultLimitsModel();
        ResourceLimits limits = config.getLimits() == null ? defaultLimits : config.getLimits().overlayOn(defaultLimits);
        return DataSourceContext.builder()
                .config(config.toView())
                .dataSource(pool)
                .dialect(dialect)
                .limits(limits)
                .build();
    }

    private void validateDsId(String dsId) {
        if (dsId == null || dsId.isBlank()) {
            throw DbAccessException.param("ds_id 不能为空");
        }
        if (!dsId.matches(DbAccessConstants.DS_ID_PATTERN)) {
            throw DbAccessException.param("ds_id 格式非法");
        }
    }

    private void validateRegisterSpec(DsConfigSpec spec) {
        if (spec.getDsId() == null || spec.getDsId().isBlank()) {
            throw DbAccessException.param("ds_id 不能为空");
        }
        if (!spec.getDsId().matches(DbAccessConstants.DS_ID_PATTERN)) {
            throw DbAccessException.param("ds_id 格式非法");
        }
        if (spec.getDsName() == null || spec.getDsName().isBlank()) {
            throw DbAccessException.param("ds_name 不能为空");
        }
        if (spec.getDbType() == null) {
            throw DbAccessException.param("不支持的数据库类型");
        }
        if (spec.getJdbcUrl() == null || !spec.getJdbcUrl().startsWith(DbAccessConstants.JDBC_URL_PREFIX)) {
            throw DbAccessException.param("jdbc_url 非法");
        }
        if (DbAccessConstants.EMBEDDED_CREDENTIAL_URL_PATTERN.matcher(spec.getJdbcUrl()).find()) {
            throw DbAccessException.param("jdbc_url 禁止内嵌账密");
        }
        if (spec.getReadonlyUser() == null || spec.getReadonlyUser().isBlank()) {
            throw DbAccessException.param("readonly_user 不能为空");
        }
        if (spec.getReadonlyPasswordRef() == null
                || !DbAccessConstants.PASSWORD_REF_PATTERN.matcher(spec.getReadonlyPasswordRef().trim()).matches()) {
            throw DbAccessException.param("readonly_password_ref 必须为 ${ENV} 形式引用，禁止明文密码");
        }
        validateLimits(spec.getLimits());
        validateWhitelist(spec.getWhitelist());
    }

    private void validateLimits(ResourceLimits limits) {
        if (limits == null) {
            return;
        }
        int moduleMaxRows = properties.defaultLimitsModel().getMaxRows();
        int moduleMaxSeconds = properties.defaultLimitsModel().getMaxSeconds();
        if (limits.getMaxRows() != null) {
            if (limits.getMaxRows() <= 0) {
                throw DbAccessException.param("白名单/限制配置格式非法：maxRows 必须为正数");
            }
            // 模块默认作为硬上界：数据源覆盖仅允许收紧，不允许放大默认上限（问题3）
            if (limits.getMaxRows() > moduleMaxRows) {
                throw DbAccessException.param("白名单/限制配置格式非法：maxRows 不得超过模块默认上限");
            }
        }
        if (limits.getMaxSeconds() != null) {
            if (limits.getMaxSeconds() <= 0) {
                throw DbAccessException.param("白名单/限制配置格式非法：maxSeconds 必须为正数");
            }
            if (limits.getMaxSeconds() > moduleMaxSeconds) {
                throw DbAccessException.param("白名单/限制配置格式非法：maxSeconds 不得超过模块默认上限");
            }
        }
    }

    /**
     * 白名单 token 允许字符集：字母/数字/下划线/点/连字符/星号/百分号（通配），
     * 禁止空白、引号、分号等注入字符。
     */
    private static final Pattern WHITELIST_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_.%*-]+$");

    /** 白名单 token 最大长度 */
    private static final int WHITELIST_TOKEN_MAX_LENGTH = 128;

    /**
     * 白名单配置合法性校验（默认放行语义下防止注入/格式异常）：
     * <ul>
     *   <li>whitelist 为 null 合法（默认放行全部表）；</li>
     *   <li>mode 缺省（null/blank）合法，按 WhitelistRule 缺省语义（有 tables 按 ALLOW_ONLY，无 tables 按 ALL）；
     *       非空必须 ∈ {ALLOW_ONLY, DENY_ONLY, ALL}；</li>
     *   <li>tables/columns 的每个 key/value 非空、trim 后长度 1~128，且仅允许
     *       {@code A-Za-z0-9_.*-%} 字符。</li>
     * </ul>
     */
    private void validateWhitelist(WhitelistRule whitelist) {
        if (whitelist == null) {
            return;
        }
        String mode = whitelist.getMode();
        if (mode != null && !mode.isBlank() && !WhitelistRule.VALID_MODES.contains(mode.trim())) {
            throw DbAccessException.param("白名单 mode 仅支持 ALLOW_ONLY/DENY_ONLY/ALL");
        }
        if (whitelist.getTables() != null) {
            for (String table : whitelist.getTables()) {
                validateWhitelistToken(table, "tables 表名");
            }
        }
        if (whitelist.getColumns() != null) {
            for (Map.Entry<String, List<String>> entry : whitelist.getColumns().entrySet()) {
                validateWhitelistToken(entry.getKey(), "columns 表名");
                if (entry.getValue() != null) {
                    for (String column : entry.getValue()) {
                        validateWhitelistToken(column, "columns 列名");
                    }
                }
            }
        }
    }

    private void validateWhitelistToken(String token, String label) {
        if (token == null) {
            throw DbAccessException.param("白名单配置格式非法：" + label + " 不能为空");
        }
        String t = token.trim();
        if (t.isEmpty()) {
            throw DbAccessException.param("白名单配置格式非法：" + label + " 不能为空");
        }
        if (t.length() > WHITELIST_TOKEN_MAX_LENGTH) {
            throw DbAccessException.param("白名单配置格式非法：" + label + " 长度不得超过 128");
        }
        if (!WHITELIST_TOKEN_PATTERN.matcher(t).matches()) {
            throw DbAccessException.param("白名单配置格式非法：" + label + " 仅允许字母数字_.*-%及通配符，禁止空白/引号/分号");
        }
    }
}
