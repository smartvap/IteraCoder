package com.agenthub.ai.datasource.constant;

/**
 * 数据源管理状态（data-model.md DsManageState）。
 *
 * <p>与 t_ds_config.status/is_deleted 的映射关系由 Service 层统一维护（见 DsConfigAdminSync），
 * 避免散落 SQL 造成状态漂移。</p>
 */
public enum DsManageState {

    /** 草稿：已登记未启用（注册后默认；连接性字段变更后回退） */
    DRAFT,

    /** 启用：只读账号+白名单满足且健康检查通过 */
    ACTIVE,

    /** 停用：拒绝新会话（可重新启用） */
    DISABLED,

    /** 已软删：历史保留，resolve 拒绝 */
    DELETED;

    /** 大小写不敏感解析；未知返回 null */
    public static DsManageState parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return DsManageState.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
