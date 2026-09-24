package com.agenthub.ai.dbaccess.store;

import com.agenthub.ai.dbaccess.config.DbAccessProperties;
import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfig;
import com.agenthub.ai.dbaccess.model.DsStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 配置驱动 DsConfigStore 默认实现（P0 首选）。
 *
 * <p>启动时将 {@code agenthub.dbaccess.datasources}（yml，等价 t_ds_config 行）载入进程内注册表，
 * {@link #save} 提供同 ds_id 幂等覆盖 + version 乐观锁语义（内存模式；P2 落库模式由 JDBC 实现替换）。
 * 凭据仅保存 ${ENV} 引用，不解析明文。</p>
 */
@Component
public class ConfigDrivenDsConfigStore implements DsConfigStore {

    private static final Logger log = LoggerFactory.getLogger(ConfigDrivenDsConfigStore.class);

    private final DbAccessProperties properties;
    private final ConcurrentHashMap<String, DsConfig> configs = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(0);

    public ConfigDrivenDsConfigStore(DbAccessProperties properties) {
        this.properties = properties;
        initFromProperties();
    }

    private void initFromProperties() {
        List<DbAccessProperties.DataSourceEntry> entries = properties.getDatasources();
        if (entries == null) {
            return;
        }
        for (DbAccessProperties.DataSourceEntry entry : entries) {
            if (entry == null || entry.getDsId() == null || entry.getDsId().isBlank()) {
                log.warn("dbaccess: 忽略缺失 ds_id 的配置数据源");
                continue;
            }
            DbType dbType = DbType.parse(entry.getDbType());
            if (dbType == null) {
                log.warn("dbaccess: 数据源[{}] 的 db_type[{}] 不受支持，跳过注册", entry.getDsId(), entry.getDbType());
                continue;
            }
            DsConfig config = toConfig(entry, dbType);
            configs.put(config.getDsId(), config);
        }
        log.info("dbaccess: 配置驱动注册数据源 {} 个", configs.size());
    }

    private DsConfig toConfig(DbAccessProperties.DataSourceEntry entry, DbType dbType) {
        LocalDateTime now = LocalDateTime.now();
        return DsConfig.builder()
                .id(idSequence.incrementAndGet())
                .dsId(entry.getDsId())
                .dsName(entry.getDsName())
                .systemCode(entry.getSystemCode())
                .dbType(dbType)
                .jdbcUrl(entry.getJdbcUrl())
                // 驱动解析统一走 DbType.resolveDriverClass（显式 > URL 前缀 > 库类型默认）
                .driverClass(dbType.resolveDriverClass(entry.getDriverClass(), entry.getJdbcUrl()))
                .readonlyUser(entry.getReadonlyUser())
                .readonlyPasswordRef(entry.getReadonlyPasswordRef())
                .ownerGroup(entry.getOwnerGroup())
                .spaceName(entry.getSpaceName())
                .spaceNames(entry.getSpaceNames())
                .whitelist(entry.getWhitelist())
                .limits(entry.getLimits())
                .status(entry.getStatus() == null ? DsStatus.ENABLED.getCode() : entry.getStatus())
                .remark(entry.getRemark())
                .version(0)
                .createTime(now)
                .updateTime(now)
                .isDeleted(0)
                .build();
    }

    @Override
    public DsConfig findByDsId(String dsId) {
        DsConfig config = configs.get(dsId);
        if (config == null || config.deleted()) {
            return null;
        }
        return copyOf(config);
    }

    @Override
    public List<DsConfig> listEnabled() {
        List<DsConfig> result = new ArrayList<>();
        for (DsConfig config : configs.values()) {
            if (config.enabled()) {
                result.add(copyOf(config));
            }
        }
        return result;
    }

    @Override
    public List<DsConfig> listAll() {
        List<DsConfig> result = new ArrayList<>();
        for (DsConfig config : configs.values()) {
            if (!config.deleted()) {
                result.add(copyOf(config));
            }
        }
        return result;
    }

    @Override
    public DsConfig save(DsConfig dsConfig) {
        if (dsConfig == null || dsConfig.getDsId() == null || dsConfig.getDsId().isBlank()) {
            throw DbAccessException.param("ds_id 不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        while (true) {
            DsConfig old = configs.get(dsConfig.getDsId());
            DsConfig next = copyOf(dsConfig);
            if (old == null) {
                next.setId(idSequence.incrementAndGet());
                next.setVersion(dsConfig.getVersion() == null ? 0 : dsConfig.getVersion());
                next.setCreateTime(now);
                next.setUpdateTime(now);
                if (configs.putIfAbsent(dsConfig.getDsId(), next) == null) {
                    return copyOf(next);
                }
            } else {
                next.setId(old.getId());
                int version = old.getVersion() == null ? 0 : old.getVersion();
                next.setVersion(version + 1);
                next.setCreateTime(old.getCreateTime());
                next.setUpdateTime(now);
                if (configs.replace(dsConfig.getDsId(), old, next)) {
                    return copyOf(next);
                }
            }
        }
    }

    private DsConfig copyOf(DsConfig source) {
        if (source == null) {
            return null;
        }
        return DsConfig.builder()
                .id(source.getId())
                .dsId(source.getDsId())
                .dsName(source.getDsName())
                .systemCode(source.getSystemCode())
                .dbType(source.getDbType())
                .jdbcUrl(source.getJdbcUrl())
                .driverClass(source.getDriverClass())
                .readonlyUser(source.getReadonlyUser())
                .readonlyPasswordRef(source.getReadonlyPasswordRef())
                .ownerGroup(source.getOwnerGroup())
                .spaceName(source.getSpaceName())
                .spaceNames(source.getSpaceNames())
                .whitelist(source.getWhitelist())
                .limits(source.getLimits())
                .status(source.getStatus())
                .remark(source.getRemark())
                .version(source.getVersion())
                .createTime(source.getCreateTime())
                .updateTime(source.getUpdateTime())
                .isDeleted(source.getIsDeleted() == null ? 0 : source.getIsDeleted())
                .build();
    }

    /** 仅供测试/管理扩展：从配置重新加载（可空实现不在当前范围） */
    protected DbAccessProperties properties() {
        return properties;
    }
}
