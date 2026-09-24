package com.agenthub.ai.dbaccess.model;

import com.agenthub.ai.dbaccess.dialect.SqlDialect;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.sql.DataSource;

/**
 * 运行时数据源上下文（api-contract DataSourceContext）。
 *
 * <p>连接池为只读账号构建（DB 账号权限层兜底拒绝写操作）；{@code DataSource} 为进程内对象，不出 SPI 方法外泄。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceContext {

    /** 脱敏配置视图 */
    private DsConfigView config;

    /** 只读连接池（每 ds_id 独立） */
    private DataSource dataSource;

    /** 方言实现 */
    private SqlDialect dialect;

    /** 资源限制（已叠加数据源覆盖层） */
    private ResourceLimits limits;
}
