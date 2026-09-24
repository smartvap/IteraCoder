package com.agenthub.ai.datasource.store;

import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfig;
import com.agenthub.ai.dbaccess.model.DsStatus;
import com.agenthub.ai.dbaccess.model.ResourceLimits;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import com.agenthub.ai.dbaccess.store.DsConfigStore;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * DB 驱动 {@link DsConfigStore} 实现（server 侧，P2 落库模式）。
 *
 * <p>背景：dbaccess 的默认实现 {@code ConfigDrivenDsConfigStore} 仅从 application.yml
 * （{@code agenthub.dbaccess.datasources}）读取且 save 只写内存，导致「数据源管理页把数据源写入
 * t_ds_config」后 {@code DataSourceRegistry} 读不到（采集/查询报 44001/1001）。本实现直接以
 * t_ds_config 为唯一数据源，打通「管理页 ↔ dbaccess 运行时」。</p>
 *
 * <p>装配：{@code @Primary} 覆盖 dbaccess 的 {@code ConfigDrivenDsConfigStore}（二者并存时注入以本类为准）；
 * 不删除配置驱动实现，纯库使用（无 DataSource / 未加载 server 组件）时仍回落 yml。本类位于 server 侧，
 * 避免 dbaccess 公共库强依赖 DataSource/JdbcTemplate。</p>
 *
 * <p>安全约束（BR-003）：readonly_password_ref 仅保存 ${ENV} 引用，本类<b>永不解析</b>明文密码，
 * 日志/异常 message 不含任何凭据。</p>
 */
@Slf4j
@Primary
@Component
public class DatabaseDsConfigStore implements DsConfigStore {

    /** t_ds_config 读取列（显式列出，禁止 SELECT *，避免列顺序/新增列耦合） */
    private static final String COLUMNS = "id, ds_id, ds_name, system_code, db_type, jdbc_url, driver_class, "
            + "readonly_user, readonly_password_ref, owner_group, space_name, space_names, "
            + "whitelist_json, limits_json, status, remark, version, create_time, update_time, is_deleted";

    private static final String SQL_FIND_BY_DS_ID =
            "SELECT " + COLUMNS + " FROM t_ds_config WHERE ds_id = ? AND is_deleted = 0";

    private static final String SQL_LIST_ENABLED =
            "SELECT " + COLUMNS + " FROM t_ds_config WHERE status = 1 AND is_deleted = 0";

    private static final String SQL_LIST_ALL =
            "SELECT " + COLUMNS + " FROM t_ds_config WHERE is_deleted = 0";

    private static final String SQL_SELECT_ID =
            "SELECT id FROM t_ds_config WHERE ds_id = ?";

    private static final String SQL_INSERT = "INSERT INTO t_ds_config (ds_id, ds_name, system_code, db_type, "
            + "jdbc_url, driver_class, readonly_user, readonly_password_ref, owner_group, space_name, space_names, "
            + "whitelist_json, limits_json, status, remark, version, create_time, update_time, is_deleted) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)";

    private static final String SQL_UPDATE = "UPDATE t_ds_config SET ds_name = ?, system_code = ?, db_type = ?, "
            + "jdbc_url = ?, driver_class = ?, readonly_user = ?, readonly_password_ref = ?, owner_group = ?, "
            + "space_name = ?, space_names = ?, whitelist_json = ?, limits_json = ?, status = ?, remark = ?, "
            + "version = version + 1, update_time = ?, is_deleted = 0 WHERE ds_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public DatabaseDsConfigStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public DsConfig findByDsId(String dsId) {
        if (dsId == null || dsId.isBlank()) {
            return null;
        }
        List<DsConfig> list = queryListByKey(SQL_FIND_BY_DS_ID, dsId);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<DsConfig> listEnabled() {
        return queryList(SQL_LIST_ENABLED);
    }

    @Override
    public List<DsConfig> listAll() {
        return queryList(SQL_LIST_ALL);
    }

    @Override
    public DsConfig save(DsConfig dsConfig) {
        if (dsConfig == null || dsConfig.getDsId() == null || dsConfig.getDsId().isBlank()) {
            throw DbAccessException.param("ds_id 不能为空");
        }
        try {
            if (existsByDsId(dsConfig.getDsId())) {
                update(dsConfig);
            } else {
                insert(dsConfig);
            }
            return findByDsId(dsConfig.getDsId());
        } catch (DataAccessException e) {
            // 对外 message 不暴露 SQL/JDBC 细节与任何凭据
            throw DbAccessException.system("数据源配置保存失败", e);
        }
    }

    /**
     * 全表条件查询包装：DB 异常记录日志并返回空列表（保持接口契约、避免影响应用启动与数据源列表接口）。
     */
    private List<DsConfig> queryList(String sql) {
        try {
            List<DsConfig> list = jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs));
            return list == null ? Collections.emptyList() : list;
        } catch (DataAccessException e) {
            log.warn("dbaccess: 读取 t_ds_config 失败（返回空结果，不影响主链路）: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 带 ds_id 条件查询包装：DB 异常记录日志并返回空列表。
     */
    private List<DsConfig> queryListByKey(String sql, String dsId) {
        try {
            List<DsConfig> list = jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), dsId);
            return list == null ? Collections.emptyList() : list;
        } catch (DataAccessException e) {
            log.warn("dbaccess: 读取 t_ds_config 失败（返回空结果，不影响主链路）: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean existsByDsId(String dsId) {
        List<Long> ids = jdbcTemplate.queryForList(SQL_SELECT_ID, Long.class, dsId);
        return ids != null && !ids.isEmpty();
    }

    private void insert(DsConfig config) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createTime = config.getCreateTime() == null ? now : config.getCreateTime();
        LocalDateTime updateTime = config.getUpdateTime() == null ? now : config.getUpdateTime();
        jdbcTemplate.update(SQL_INSERT,
                config.getDsId(),
                config.getDsName(),
                config.getSystemCode(),
                config.getDbType() == null ? null : config.getDbType().getCode(),
                config.getJdbcUrl(),
                resolveDriverClass(config),
                config.getReadonlyUser(),
                config.getReadonlyPasswordRef(),
                config.getOwnerGroup(),
                config.getSpaceName(),
                spaceNamesJson(config),
                whitelistJson(config),
                limitsJson(config),
                config.getStatus() == null ? DsStatus.ENABLED.getCode() : config.getStatus(),
                config.getRemark(),
                config.getVersion() == null ? 0 : config.getVersion(),
                Timestamp.valueOf(createTime),
                Timestamp.valueOf(updateTime));
    }

    private void update(DsConfig config) {
        jdbcTemplate.update(SQL_UPDATE,
                config.getDsName(),
                config.getSystemCode(),
                config.getDbType() == null ? null : config.getDbType().getCode(),
                config.getJdbcUrl(),
                resolveDriverClass(config),
                config.getReadonlyUser(),
                config.getReadonlyPasswordRef(),
                config.getOwnerGroup(),
                config.getSpaceName(),
                spaceNamesJson(config),
                whitelistJson(config),
                limitsJson(config),
                config.getStatus() == null ? DsStatus.ENABLED.getCode() : config.getStatus(),
                config.getRemark(),
                Timestamp.valueOf(LocalDateTime.now()),
                config.getDsId());
    }

    /**
     * 驱动类解析：统一走 DbType.resolveDriverClass（显式 &gt; URL 前缀 &gt; 库类型默认），
     * 保证存量 jdbc:mysql: 的 OceanBase-MySQL 仍解析为 mysql 驱动，行为不变。
     */
    private String resolveDriverClass(DsConfig config) {
        if (config.getDbType() == null) {
            // db_type 缺失时仅能保留显式驱动（与既有无 dbType 兜底语义一致）
            return config.getDriverClass();
        }
        return config.getDbType().resolveDriverClass(config.getDriverClass(), config.getJdbcUrl());
    }

    /** space_names：List → JSON 文本（空/null → null，与「回落 spaceName 单库」语义一致） */
    private String spaceNamesJson(DsConfig config) {
        List<String> names = config.getSpaceNames();
        return (names == null || names.isEmpty()) ? null : JSON.toJSONString(names);
    }

    private String whitelistJson(DsConfig config) {
        return config.getWhitelist() == null ? null : config.getWhitelist().toJson();
    }

    private String limitsJson(DsConfig config) {
        return config.getLimits() == null ? null : JSON.toJSONString(config.getLimits());
    }

    /**
     * t_ds_config 行 → dbaccess {@link DsConfig}。
     *
     * <p>密码仅保留 {@code readonlyPasswordRef} 引用，<b>不解析环境变量</b>。</p>
     */
    DsConfig mapRow(ResultSet rs) throws SQLException {
        return DsConfig.builder()
                .id(rs.getLong("id"))
                .dsId(rs.getString("ds_id"))
                .dsName(rs.getString("ds_name"))
                .systemCode(rs.getString("system_code"))
                .dbType(DbType.parse(rs.getString("db_type")))
                .jdbcUrl(rs.getString("jdbc_url"))
                .driverClass(rs.getString("driver_class"))
                .readonlyUser(rs.getString("readonly_user"))
                .readonlyPasswordRef(rs.getString("readonly_password_ref"))
                .ownerGroup(rs.getString("owner_group"))
                .spaceName(rs.getString("space_name"))
                .spaceNames(parseSpaceNames(rs.getString("space_names")))
                .whitelist(WhitelistRule.fromJson(rs.getString("whitelist_json")))
                .limits(parseLimits(rs.getString("limits_json")))
                .status(rs.getInt("status"))
                .remark(rs.getString("remark"))
                .version(rs.getInt("version"))
                .createTime(toLocalDateTime(rs.getTimestamp("create_time")))
                .updateTime(toLocalDateTime(rs.getTimestamp("update_time")))
                .isDeleted(rs.getInt("is_deleted"))
                .build();
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /** space_names JSON 文本 → List（空/null/非法均回落 null，与 whitelist 缺省语义一致） */
    private static List<String> parseSpaceNames(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            List<String> names = JSON.parseArray(json, String.class);
            return names == null || names.isEmpty() ? null : names;
        } catch (Exception e) {
            return null;
        }
    }

    private static ResourceLimits parseLimits(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json, ResourceLimits.class);
        } catch (Exception e) {
            return null;
        }
    }
}
