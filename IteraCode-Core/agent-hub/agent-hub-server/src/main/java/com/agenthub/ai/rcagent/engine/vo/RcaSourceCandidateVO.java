package com.agenthub.ai.rcagent.engine.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * RCAgent 候选数据源明细（api-contract.md §5.6）。
 *
 * <p>仅承载非敏感字段（ds_id / ds_name / db_type / manage_state），
 * 严禁携带 jdbc_url、readonly_user、readonly_password_ref、whitelist_json（AGENTS.md 5.x、data-model.md §7 DM-10）。</p>
 */
@Data
public class RcaSourceCandidateVO {

    /** 数据源 ID */
    private String dsId;

    /** 数据源显示名 */
    private String dsName;

    /** 库类型（MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE） */
    private String dbType;

    /** 管理状态（候选源恒为 ACTIVE） */
    private String manageState;

    /**
     * 候选源归属系统编码：仅服务端分组与同组校验使用，不对外暴露
     * （api-contract.md §5.6 对外字段仅 dsId/dsName/dbType/manageState；§4.4 响应示例亦不含该字段）。
     * 由候选源只读 Mapper 从 {@code t_ds_config.system_code} 映射。
     */
    @JsonIgnore
    private String systemCode;
}
