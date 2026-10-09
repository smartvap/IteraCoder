package com.agenthub.ai.rcagent.feedback.adapter;

import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ds 配置只读解析（module-002 / db-access-common，brownfield 只读复用）。
 *
 * <p>按 ds_id 解析 SQL 方言（传给 module-006 parse）与库 B 空间名（写 llm-wiki）；
 * ds 未注册/解析失败时降级 MYSQL / 空空间（不破坏闭环，依赖异常由调用方处理）。</p>
 */
@Slf4j
@Component
public class DsConfigResolver {

    private static final String DEFAULT_DB_TYPE = "MYSQL";

    private final DataSourceRegistry dataSourceRegistry;

    public DsConfigResolver(DataSourceRegistry dataSourceRegistry) {
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /** 解析 dbType；ds 未注册返回默认 MYSQL */
    public String resolveDbType(String dsId) {
        try {
            var config = dataSourceRegistry.getConfig(dsId);
            if (config != null && config.getDbType() != null && !config.getDbType().isBlank()) {
                return config.getDbType();
            }
        } catch (Exception e) {
            log.warn("feedback: ds dbType 解析失败 dsId={} 降级 MYSQL: {}", safeDs(dsId), e.getMessage());
        }
        return DEFAULT_DB_TYPE;
    }

    /** 解析 llm-wiki 空间名；ds 未注册/无 space_name 返回 null */
    public String resolveSpaceName(String dsId) {
        try {
            var config = dataSourceRegistry.getConfig(dsId);
            if (config != null && config.getSpaceName() != null && !config.getSpaceName().isBlank()) {
                return config.getSpaceName();
            }
        } catch (Exception e) {
            log.warn("feedback: ds space_name 解析失败 dsId={}: {}", safeDs(dsId), e.getMessage());
        }
        return null;
    }

    private String safeDs(String dsId) {
        return dsId == null ? "" : dsId;
    }
}
