package com.agenthub.ai.datasource.vo;

import lombok.Data;

/**
 * 启停/删除等状态操作结果（api-contract delete/disable/enable 响应）。
 */
@Data
public class DsStateResultVO {

    private String dsId;

    /** 操作后管理状态 ACTIVE/DISABLED/DELETED */
    private String manageState;

    /** 启用时健康摘要（PASS 通过） */
    private HealthCheckResultVO health;

    /** 删除时下游采集/检索引用提示 */
    private String warn;
}
