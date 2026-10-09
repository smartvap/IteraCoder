package com.agenthub.ai.rcagent.sqlguard.adapter;

import com.agenthub.ai.dbaccess.exception.DbAccessException;
import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.dbaccess.model.DsConfigView;
import com.agenthub.ai.dbaccess.provider.MetadataProvider;
import com.agenthub.ai.dbaccess.provider.MetadataProviderRegistry;
import com.agenthub.ai.dbaccess.registry.DataSourceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * db-access-common 运行时元数据适配器（L2 Schema 降级通道）。
 *
 * <p>kb 字典缺失时经 {@link MetadataProviderRegistry} 拉取运行时列元数据做表/列存在性与类型校验；
 * 数据源不可达/Provider 缺失时返回空列表（配合 source=UNAVAILABLE fail 倾向保守处置）。</p>
 *
 * <p>物理库定位取 {@link DataSourceRegistry} 的 {@code spaceNames}（可访问库边界）而非 {@code spaceName}：
 * 知识空间绑定会把 space_name 改写为 llm-wiki 空间名（非物理库名），若沿用会使真实表定位到库边界外被拒绝，
 * 造成"表未知"误杀（BLOCK）。</p>
 */
@Slf4j
@Component
public class MetadataProviderAdapter {

    private final MetadataProviderRegistry providerRegistry;
    private final DataSourceRegistry dataSourceRegistry;

    public MetadataProviderAdapter(MetadataProviderRegistry providerRegistry,
                                   DataSourceRegistry dataSourceRegistry) {
        this.providerRegistry = providerRegistry;
        this.dataSourceRegistry = dataSourceRegistry;
    }

    /**
     * 运行时列元数据查询（降级 L2 用，旧签名：不带 schema，保持向后兼容）。
     *
     * @return 空列表表示不可用/表不存在（调用方结合上下文判定）
     */
    public List<ColumnMeta> listColumns(String dsId, String tableName, String dbType) {
        return listColumns(dsId, null, tableName, dbType);
    }

    /**
     * 运行时列元数据查询（降级 L2 用，带 schema 限定）。
     *
     * <p>候选物理库由 {@link #resolveCandidateSpaces(String, String)} 决定：schema 显式指定时优先，
     * 否则按数据源 space_names 逐库探测；单个候选查询失败/为空时继续下一个，命中即短路返回。</p>
     *
     * @return 空列表表示不可用/表不存在（调用方结合上下文判定）
     */
    public List<ColumnMeta> listColumns(String dsId, String schema, String tableName, String dbType) {
        DbType type = DbType.parse(dbType);
        if (type == null) {
            return new ArrayList<>();
        }
        for (String space : resolveCandidateSpaces(dsId, schema)) {
            try {
                MetadataProvider provider = providerRegistry.providerOf(type);
                MetadataRequest.MetadataRequestBuilder builder = MetadataRequest.builder()
                        .dsId(dsId)
                        .includeColumns(true)
                        .includeStats(false);
                // 候选非空时才覆盖 spaceName；候选为 null 表示交由 Provider 自行回落 config.spaceName/连接 catalog
                if (space != null && !space.isBlank()) {
                    builder.spaceName(space);
                }
                List<ColumnMeta> metas = provider.listColumns(builder.build(), tableName);
                if (metas != null && !metas.isEmpty()) {
                    // 命中即短路，避免对其余候选发起多余查询
                    return metas;
                }
            } catch (DbAccessException ex) {
                log.warn("sqlguard: 运行时元数据不可用(dsId={},space={},table={}): {}",
                        dsId, space, tableName, safe(ex.getMessage()));
            } catch (RuntimeException ex) {
                log.warn("sqlguard: 运行时元数据查询异常(dsId={},space={},table={}): {}",
                        dsId, space, tableName, safe(ex.getMessage()));
            }
        }
        return new ArrayList<>();
    }

    /**
     * 表行数估计（大表全扫判断辅助，降级场景尽力）。
     *
     * <p>与 {@link #listColumns} 同口径遍历候选物理库，任一候选命中目标表即返回其行数估计。</p>
     */
    public Long estimateTableRows(String dsId, String tableName, String dbType) {
        DbType type = DbType.parse(dbType);
        if (type == null) {
            return null;
        }
        String target = tableName == null ? "" : tableName.toUpperCase(Locale.ROOT);
        for (String space : resolveCandidateSpaces(dsId, null)) {
            try {
                MetadataProvider provider = providerRegistry.providerOf(type);
                MetadataRequest.MetadataRequestBuilder builder = MetadataRequest.builder()
                        .dsId(dsId)
                        .includeColumns(false)
                        .includeStats(true);
                if (space != null && !space.isBlank()) {
                    builder.spaceName(space);
                }
                List<TableMeta> tables = provider.listTables(builder.build());
                if (tables == null) {
                    continue;
                }
                for (TableMeta t : tables) {
                    if (target.equals(t.getTableName() == null ? "" : t.getTableName().toUpperCase(Locale.ROOT))) {
                        return t.getRowCountEstimate();
                    }
                }
            } catch (RuntimeException ex) {
                log.debug("sqlguard: 行数估计失败(dsId={},space={},table={})", dsId, space, tableName);
            }
        }
        return null;
    }

    /**
     * 解析候选物理库/Schema 列表（有序去重）。
     *
     * <p>修复背景：知识空间绑定会把 t_ds_config.space_name 改写为 llm-wiki 空间名（如 CRM_SD），
     * 真实物理库记录在 space_names 可访问库集合（如 ai_server）。若沿用 config.spaceName 回落，
     * 候选空间会落在库边界外被 Provider 拒绝返回空列，导致真实表被误判未知而 BLOCK。</p>
     *
     * <p>回落语义：space_names 不可用（空/null/取配置异常）时返回单元素 null，
     * 交由 Provider 按 config.spaceName/连接 catalog 自行回落，保持未绑定空间数据源的旧行为。</p>
     */
    private List<String> resolveCandidateSpaces(String dsId, String explicitSchema) {
        // 调用方显式指定 schema 时优先级最高，不做多库探测
        if (explicitSchema != null && !explicitSchema.isBlank()) {
            return List.of(explicitSchema);
        }
        try {
            DsConfigView config = dataSourceRegistry.getConfig(dsId);
            List<String> spaceNames = config == null ? null : config.getSpaceNames();
            if (spaceNames != null && !spaceNames.isEmpty()) {
                Set<String> distinct = new LinkedHashSet<>();
                for (String name : spaceNames) {
                    if (name == null) {
                        continue;
                    }
                    String trimmed = name.trim();
                    if (!trimmed.isEmpty()) {
                        distinct.add(trimmed);
                    }
                }
                if (!distinct.isEmpty()) {
                    return new ArrayList<>(distinct);
                }
            }
        } catch (DbAccessException ex) {
            log.warn("sqlguard: 数据源配置不可用(dsId={})，回落默认空间: {}", dsId, safe(ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("sqlguard: 数据源配置读取异常(dsId={})，回落默认空间: {}", dsId, safe(ex.getMessage()));
        }
        return Collections.singletonList(null);
    }

    private String safe(String msg) {
        if (msg == null || msg.isBlank()) {
            return "无细节";
        }
        return msg.length() > 200 ? msg.substring(0, 200) : msg;
    }
}
