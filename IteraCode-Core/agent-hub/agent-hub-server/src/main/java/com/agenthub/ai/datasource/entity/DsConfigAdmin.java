package com.agenthub.ai.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 数据源管理扩展表（t_ds_config_admin）：承载完整管理状态机与空间绑定态、最近健康摘要。
 */
@Data
@TableName("t_ds_config_admin")
public class DsConfigAdmin {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源标识（逻辑关联 t_ds_config.ds_id，唯一） */
    private String dsId;

    /** 管理状态：DRAFT/ACTIVE/DISABLED/DELETED */
    private String manageState;

    /** 空间绑定态：NOT_APPLIED/BOUND/BIND_PENDING/BIND_FAILED */
    private String spaceBindState;

    /** 空间绑定失败原因摘要（脱敏） */
    private String spaceBindError;

    /** 最近健康状态：UNCHECKED/PASS/FAIL */
    private String lastHealthState;

    private Date lastHealthTime;

    /** 最近健康摘要 JSON（脱敏，方言/只读/字典/白名单逐项 ok） */
    private String lastHealthSummary;

    /** 创建操作人 */
    private Long creatorId;

    private Date createTime;

    private Date updateTime;

    /** 逻辑删除标记（随数据源软删置 1） */
    private Integer isDeleted;
}
