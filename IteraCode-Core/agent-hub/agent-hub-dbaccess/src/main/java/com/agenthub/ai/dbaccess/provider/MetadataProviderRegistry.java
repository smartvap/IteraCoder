package com.agenthub.ai.dbaccess.provider;

import com.agenthub.ai.dbaccess.exception.DbAccessErrorCode;
import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.model.DbType;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MetadataProvider 注册表：Provider 自动发现与路由，含 JdbcFallback 兜底。
 *
 * <p>路由规则：优先返回 supports(dbType) 的非兜底 Provider；无特定 Provider 时返回 JdbcFallback；
 * 目标库类型无任何 Provider 且 fallback 不可用时抛 {@link DbAccessErrorCode#NO_PROVIDER}。</p>
 */
@Component
public class MetadataProviderRegistry {

    private final List<MetadataProvider> providers;
    private final JdbcFallbackProvider fallback;

    public MetadataProviderRegistry(List<MetadataProvider> providers, JdbcFallbackProvider fallback) {
        this.providers = providers;
        this.fallback = fallback;
    }

    /**
     * 按 db_type 路由 Provider。
     *
     * @throws DbAccessException 无可用 Provider 时抛 NO_PROVIDER
     */
    public MetadataProvider providerOf(DbType dbType) {
        if (dbType == null) {
            throw new DbAccessException(DbAccessErrorCode.NO_PROVIDER, "无可用元数据 Provider");
        }
        if (providers != null) {
            for (MetadataProvider provider : providers) {
                if (!(provider instanceof JdbcFallbackProvider) && provider.supports(dbType)) {
                    return provider;
                }
            }
        }
        if (fallback != null && fallback.supports(dbType)) {
            return fallback;
        }
        throw new DbAccessException(DbAccessErrorCode.NO_PROVIDER,
                "无可用元数据 Provider：" + dbType.getDisplayName());
    }

    /**
     * 已装配的 Provider 列表（扩展性：新增 db_type 只需注册新 Provider 装配）。
     */
    public List<MetadataProvider> allProviders() {
        return providers;
    }
}
