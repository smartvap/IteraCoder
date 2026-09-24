package com.agenthub.ai.datasource.vo;

import lombok.Getter;
import lombok.Setter;

/**
 * 数据源脱敏详情（api-contract DsConfigDetailVO）：配置 + 管理态 + 最近健康摘要。
 */
@Getter
@Setter
public class DsConfigDetailVO extends DsConfigVO {

    /** 最近健康摘要（最新一条 t_ds_config_health；从未检查为 null） */
    private HealthCheckResultVO lastHealth;
}
