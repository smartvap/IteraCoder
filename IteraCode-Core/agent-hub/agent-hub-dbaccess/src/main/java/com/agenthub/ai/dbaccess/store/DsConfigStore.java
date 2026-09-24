package com.agenthub.ai.dbaccess.store;

import com.agenthub.ai.dbaccess.model.DsConfig;

import java.util.List;

/**
 * 数据源配置存储 SPI（api-contract DsConfigStore：抽象 + 配置驱动默认实现）。
 *
 * <p>本抽象封装 t_ds_config 的读取/登记能力：P0 由 {@link ConfigDrivenDsConfigStore}（application.yml 配置驱动）
 * 提供默认实现；P2 起 multi-datasource-admin 落库时可由 JDBC 实现替换，本模块其余代码仅依赖该接口。</p>
 */
public interface DsConfigStore {

    /**
     * 按 ds_id 查找（不区分启停，已逻辑删除返回 null）。
     *
     * @param dsId 数据源标识
     * @return 配置或 null
     */
    DsConfig findByDsId(String dsId);

    /**
     * 列出启用数据源（status=1 且未逻辑删除）。
     */
    List<DsConfig> listEnabled();

    /**
     * 列出全部数据源（不含已逻辑删除，供管理/审计展示）。
     */
    List<DsConfig> listAll();

    /**
     * 幂等保存/覆盖（同 ds_id 覆盖 + version 乐观锁递增），供 register SPI 写入。
     *
     * @param dsConfig 待保存配置
     * @return 保存后的配置（含最新 id/version/时间）
     */
    DsConfig save(DsConfig dsConfig);
}
