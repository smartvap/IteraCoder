package com.agenthub.ai.datasource.dto;

import lombok.Data;

/**
 * 数据源列表分页查询（api-contract DsConfigPageQueryDTO，Query 参数）。
 */
@Data
public class DsConfigPageQueryDTO {

    /** 页码，默认 1 */
    private Integer page = 1;

    /** 每页条数，默认 10 */
    private Integer pageSize = 10;

    /** 管理状态 DRAFT/ACTIVE/DISABLED/DELETED（空=除 DELETED 外） */
    private String manageState;

    /** 归属系统过滤 */
    private String systemCode;

    /** 库类型过滤 */
    private String dbType;

    /** 关键字（模糊 ds_name/ds_id） */
    private String keyword;

    /** 按空间名过滤 */
    private String spaceName;
}
