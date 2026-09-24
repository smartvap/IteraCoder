package com.agenthub.ai.dbaccess.registry;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfig;
import com.alibaba.druid.pool.DruidDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 只读连接池工厂：每 ds_id 构建独立 Druid 池（只读账号 + 连接只读模式 + 健康检查）。
 *
 * <p>安全要点：密码经 {@link EnvPlaceholderResolver} 解析，构建参数不打印；连接失败异常 message 不含地址凭据。</p>
 */
@Component
public class ReadonlyDataSourceFactory {

    private static final Logger log = LoggerFactory.getLogger(ReadonlyDataSourceFactory.class);

    private final EnvPlaceholderResolver placeholderResolver;

    public ReadonlyDataSourceFactory(EnvPlaceholderResolver placeholderResolver) {
        this.placeholderResolver = placeholderResolver;
    }

    /**
     * 构建并健康检查只读 Druid 数据源（未做缓存，由 DataSourceRegistry 按 ds_id 复用）。
     *
     * @throws DbAccessException DS_NOT_READY（密码/环境变量缺失）、CONNECTION_FAILED（不可达）
     */
    public DruidDataSource create(DsConfig config) {
        if (config == null || config.getDsId() == null || config.getDsId().isBlank()) {
            throw DbAccessException.param("ds_id 不能为空");
        }
        String dsId = config.getDsId();
        // 1) 解析密码（仅 ${ENV} 引用；缺失快速失败，不泄露引用语义之外信息）
        String password = placeholderResolver.resolvePasswordRef(config.getReadonlyPasswordRef());
        // 2) 解析连接串/账号中的 ${ENV} 占位
        String url = placeholderResolver.resolveAll(config.getJdbcUrl());
        String user = placeholderResolver.resolveAll(config.getReadonlyUser());
        // 驱动解析统一走 DbType.resolveDriverClass（显式 > URL 前缀 > 库类型默认），
        // 保证存量 jdbc:mysql: 的 OceanBase-MySQL 仍走 mysql-connector；再解析显式值中的 ${ENV} 占位
        String resolvedDriver = config.getDbType() == null
                ? config.getDriverClass()
                : config.getDbType().resolveDriverClass(config.getDriverClass(), url);
        String driver = placeholderResolver.resolveAll(resolvedDriver);

        DruidDataSource ds = new DruidDataSource();
        ds.setName("dbaccess-" + dsId);
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(password);
        ds.setDriverClassName(driver);
        // 每 ds_id 独立小池：空闲回收、避免跨 ds 资源串扰
        ds.setInitialSize(0);
        ds.setMinIdle(0);
        ds.setMaxActive(4);
        ds.setMaxWait(3000);
        ds.setValidationQuery(validationQuery(config.getDbType()));
        ds.setTestWhileIdle(true);
        ds.setTestOnBorrow(true);
        ds.setDefaultReadOnly(true); // 应用层只读兜底
        ds.setConnectionErrorRetryAttempts(1);
        ds.setBreakAfterAcquireFailure(false);
        try {
            ds.init();
            // 健康检查：3 秒内取到连接且有效
            try (Connection conn = ds.getConnection(3000)) {
                if (conn == null || !conn.isValid(3)) {
                    throw new DbAccessException(DbAccessErrorCode.DS_NOT_READY, "数据源未就绪：健康检查失败");
                }
            }
            return ds;
        } catch (SQLException e) {
            closeQuietly(ds);
            throw new DbAccessException(DbAccessErrorCode.CONNECTION_FAILED, "数据库连接失败", e);
        }
    }

    private String validationQuery(DbType dbType) {
        return dbType != null && dbType.isOracleFamily() ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }

    private void closeQuietly(DruidDataSource ds) {
        try {
            if (!ds.isClosed()) {
                ds.close();
            }
        } catch (Exception ignored) {
            log.debug("dbaccess: 关闭失败连接池忽略异常");
        }
    }
}
