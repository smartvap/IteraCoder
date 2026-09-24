package com.agenthub.ai.datasource.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 数据源注册核心表（t_ds_config，module-002 DDL 定义，本模块管理壳读写）。
 *
 * <p>本实体不携带明文密码：readonlyPasswordRef 仅存 ${ENV} 形式引用；
 * jdbcUrl 禁止内嵌账密；whitelistJson/limitsJson 存 JSON 文本。</p>
 */
@Data
@TableName("t_ds_config")
public class DsConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局唯一数据源标识（一经注册不可变更 BR-003） */
    private String dsId;

    /** 数据源显示名 */
    private String dsName;

    /** 归属系统编码（CRM/OA/...） */
    private String systemCode;

    /** 库类型：MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE */
    private String dbType;

    /** JDBC 连接串（禁止内嵌账密） */
    private String jdbcUrl;

    /** JDBC 驱动类名（可空，按 db_type 推断） */
    private String driverClass;

    /** 只读账号用户名 */
    private String readonlyUser;

    /** 只读密码环境变量引用，形如 ${DBACCESS_DS_XXX_PWD}（永不存明文） */
    private String readonlyPasswordRef;

    /** 属主组/责任团队 */
    private String ownerGroup;

    /** Schema/Space 名 */
    private String spaceName;

    /**
     * 可访问库集合 JSON 文本（space_names，如 ["CRM_A","CRM_B"]；可空）。
     * 语义：非空时以它为库集合边界；为空/null 时回落 spaceName（旧行为，单库）。
     */
    @TableField("space_names")
    private String spaceNamesJson;

    /** 白名单 JSON 文本（tables/columns/mode） */
    private String whitelistJson;

    /** 资源限制覆盖 JSON 文本（maxRows/maxSeconds/sensitivePolicy/allowSelectStar） */
    private String limitsJson;

    /** 状态：1=启用 0=停用（module-002 最小可解析态） */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 乐观锁版本 */
    private Integer version;

    private Date createTime;

    private Date updateTime;

    /** 逻辑删除：0=正常 1=删除 */
    private Integer isDeleted;
}
