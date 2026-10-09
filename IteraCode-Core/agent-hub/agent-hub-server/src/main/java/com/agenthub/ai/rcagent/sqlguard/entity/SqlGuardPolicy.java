package com.agenthub.ai.rcagent.sqlguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 安全策略配置实体（sql_guard_policy，data-model.md §1）。
 */
@Data
@TableName(value = "sql_guard_policy")
public class SqlGuardPolicy {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 策略作用域：GLOBAL / DS */
    private String policyScope;

    /** 数据源实例 ID（GLOBAL 行为空） */
    private String dsId;

    /** 策略名/备注标识 */
    private String policyName;

    /** 最大返回行数默认值（1~50000） */
    private Integer maxRowsDefault;

    /** 最大执行时间默认值（秒，1~60） */
    private Integer maxSecondsDefault;

    /** SELECT * 策略：BLOCK / ALLOW */
    private String selectStarPolicy;

    /** BLOCK 判定总开关（1=开启 0=关闭降级） */
    private Integer blockEnabled;

    /** 敏感字段处理策略：MASK / DENY / ALLOW_BY_PERMISSION */
    private String sensitivePolicy;

    /** 敏感字段清单来源：REGISTRY / COMPLIANCE */
    private String sensitiveSource;

    /** LOW 级自动放行只读执行开关 */
    private Integer lowAutoEnabled;

    /** MVP 全量人工审核开关 */
    private Integer mvpReviewAll;

    /** HIGH 级强制人工审核 */
    private Integer highReviewRequired;

    /** 失败回退重新生成最大轮数 */
    private Integer repairMaxRounds;

    /** 允许方言（JSON 数组文本，如 ["MYSQL","ORACLE"]，可空=全允许） */
    private String dialectWhitelist;

    /** 配置版本号（热加载失效键，更新+1） */
    private Integer configVersion;

    /** 备注 */
    private String remark;

    private Date createTime;

    private Date updateTime;

    /** 逻辑删除 0/1 */
    private Integer isDeleted;
}
