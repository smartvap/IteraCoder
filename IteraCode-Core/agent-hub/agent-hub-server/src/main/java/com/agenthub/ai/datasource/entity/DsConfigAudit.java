package com.agenthub.ai.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 数据源变更审计表（t_ds_config_audit）。
 *
 * <p>安全约束（BR-002/AGENTS.md 5.4）：敏感字段只记 changed 标记与字段名，
 * 不落任何密码/内嵌账密连接串明文；changeDetail 为脱敏 JSON 文本。</p>
 */
@Data
@TableName("t_ds_config_audit")
public class DsConfigAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源标识 */
    private String dsId;

    /** 动作：REGISTER/UPDATE/ENABLE/DISABLE/DELETE/TEST_CONNECTION/HEALTH_CHECK/SPACE_BIND */
    private String action;

    /** 操作人 ID（BaseContext 占位鉴权） */
    private Long operatorId;

    /** 操作人显示名（可空） */
    private String operatorName;

    /** 变更摘要（不含密码值） */
    private String changeSummary;

    /** 变更明细 JSON（敏感字段只记 changed 标记不记值） */
    private String changeDetail;

    /** SUCCESS/FAIL */
    private String bizResult;

    /** 失败原因（脱敏） */
    private String errorMessage;

    private Date createTime;
}
