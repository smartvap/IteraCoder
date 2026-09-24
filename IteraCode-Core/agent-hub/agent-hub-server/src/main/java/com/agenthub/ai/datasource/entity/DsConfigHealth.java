package com.agenthub.ai.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 数据源健康检查历史表（t_ds_config_health）。
 *
 * <p>每次"测试连接"或"健康检查"生成一条结构化诊断记录；诊断明细为脱敏结果，不落连接串/密码。</p>
 */
@Data
@TableName("t_ds_config_health")
public class DsConfigHealth {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源标识（测试连接未注册可空；健康检查必填） */
    private String dsId;

    /** HEALTH_CHECK / TEST_CONNECTION */
    private String checkType;

    /** PASS/FAIL/PARTIAL */
    private String checkResult;

    /** 方言识别结果（MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE/NOT_IDENTIFIED） */
    private String dialectIdentified;

    /** 连通性：1=可达 0=不可达 */
    private Integer connectionOk;

    /** 只读账号校验：1=通过 0=失败 */
    private Integer readonlyOk;

    /** 字典权限探测：1=可查 0=失败 */
    private Integer dictionaryOk;

    /** 白名单检查：1=满足 0=不满足（BR-001） */
    private Integer whitelistOk;

    /** 失败项枚举 JSON 数组（connection/account/permission/driver/dictionary/whitelist/space） */
    private String failureItems;

    /** 脱敏失败原因（禁止地址凭据/密码） */
    private String errorMessage;

    /** 检查耗时 ms */
    private Long costMs;

    /** 触发人 */
    private Long operatorId;

    private Date createTime;
}
