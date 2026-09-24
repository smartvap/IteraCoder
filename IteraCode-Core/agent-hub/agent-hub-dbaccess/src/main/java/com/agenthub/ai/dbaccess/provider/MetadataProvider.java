package com.agenthub.ai.dbaccess.provider;

import com.agenthub.ai.dbaccess.meta.ColumnMeta;
import com.agenthub.ai.dbaccess.meta.MetadataRequest;
import com.agenthub.ai.dbaccess.meta.RelationMeta;
import com.agenthub.ai.dbaccess.meta.TableMeta;
import com.agenthub.ai.dbaccess.model.DbType;

import java.util.List;

/**
 * 元数据 Provider SPI（api-contract MetadataProvider）。
 *
 * <p>按 db_type 采集中立元数据；Provider 通过 Spring 装配自动注册，无可用 Provider 时由 JdbcFallback 兜底。</p>
 */
public interface MetadataProvider {

    /** Provider 标识（mysql/oracle/fallback 等，写入 TableMeta.provider） */
    String providerName();

    /** 是否支持给定库类型 */
    boolean supports(DbType dbType);

    /**
     * 列出表元数据（includeColumns=true 时 TableMeta 自带列清单）。
     */
    List<TableMeta> listTables(MetadataRequest request);

    /**
     * 列出指定表的列元数据。
     */
    List<ColumnMeta> listColumns(MetadataRequest request, String tableName);

    /**
     * 列出指定表的关系元数据。
     */
    List<RelationMeta> listRelations(MetadataRequest request, String tableName);
}
