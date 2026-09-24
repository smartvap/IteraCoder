package com.agenthub.ai.datasource.constant;

/**
 * 数据源变更审计动作（data-model.md AuditAction）。
 */
public enum AuditAction {

    /** 注册数据源 */
    REGISTER,

    /** 修改数据源 */
    UPDATE,

    /** 启用数据源 */
    ENABLE,

    /** 停用数据源 */
    DISABLE,

    /** 删除数据源（软删） */
    DELETE,

    /** 测试连接 */
    TEST_CONNECTION,

    /** 健康检查 */
    HEALTH_CHECK,

    /** 空间绑定/重试 */
    SPACE_BIND
}
