package com.agenthub.ai.datasource.service;

import com.agenthub.ai.datasource.constant.DsManageState;

/**
 * t_ds_config.status/is_deleted ↔ t_ds_config_admin.manage_state 映射工具。
 *
 * <p>状态一致性由 Service 层统一维护，避免散落 SQL 造成漂移（data-model 注意事项 10）。</p>
 */
public final class DsConfigAdminSync {

    private DsConfigAdminSync() {
    }

    /**
     * manage_state → t_ds_config.status（1=启用 0=停用）。
     */
    public static int toConfigStatus(DsManageState manageState) {
        return manageState == DsManageState.ACTIVE ? 1 : 0;
    }

    /**
     * manage_state → t_ds_config.is_deleted（0/1）。
     */
    public static int toConfigDeleted(DsManageState manageState) {
        return manageState == DsManageState.DELETED ? 1 : 0;
    }
}
