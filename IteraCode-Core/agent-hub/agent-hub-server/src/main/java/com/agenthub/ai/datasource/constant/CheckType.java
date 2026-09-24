package com.agenthub.ai.datasource.constant;

/**
 * 健康检查类型（data-model.md CheckType）。
 */
public enum CheckType {

    /** 表单填写后测试（候选配置，一次性凭据，不落库主表） */
    TEST_CONNECTION,

    /** 对已登记数据源执行健康检查（启用前/主动触发） */
    HEALTH_CHECK
}
