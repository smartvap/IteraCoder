package com.agenthub.ai.datasource.vo;

import com.agenthub.ai.dbaccess.model.ResourceLimits;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 数据源脱敏视图（api-contract DsConfigVO）。
 *
 * <p>列表/注册返回均不含明文密码；readonlyPasswordRef 仅 ${ENV} 引用。
 * whitelistJson/limitsJson 为 XML 分页联查时的内部中转列，对外以 whitelist/limits 对象返回。</p>
 */
@Data
public class DsConfigVO {

    /** 数据源标识 */
    private String dsId;

    /** 显示名 */
    private String dsName;

    /** 归属系统编码 */
    private String systemCode;

    /** 库类型 */
    private String dbType;

    /** JDBC 连接串（不含账密） */
    private String jdbcUrl;

    /** 驱动类名 */
    private String driverClass;

    /** 只读账号 */
    private String readonlyUser;

    /** 密码引用（${ENV} 形式） */
    private String readonlyPasswordRef;

    /** 属主组 */
    private String ownerGroup;

    /** 知识空间 */
    private String spaceName;

    /**
     * 可访问库集合（多库边界；为空/null 时回落 spaceName 单库）。
     * 对应 space_names JSON 列，如 ["CRM_A","CRM_B"]。
     */
    private List<String> spaceNames;

    /** 白名单对象（对外） */
    private WhitelistRule whitelist;

    /** 限制对象（对外） */
    private ResourceLimits limits;

    /** 状态：1=启用 0=停用 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 乐观锁版本（编辑回填） */
    private Integer version;

    /** 管理状态 DRAFT/ACTIVE/DISABLED/DELETED */
    private String manageState;

    /** 空间绑定态 */
    private String spaceBindState;

    /** 空间绑定失败原因（脱敏） */
    private String spaceBindError;

    /** 最近健康状态 UNCHECKED/PASS/FAIL */
    private String lastHealthState;

    /** 最近健康时间 */
    private Date lastHealthTime;

    private Date createTime;

    private Date updateTime;

    // ===== 内部中转列（XML 结果映射，对外忽略） =====

    @JsonIgnore
    private String whitelistJson;

    @JsonIgnore
    private String limitsJson;

    @JsonIgnore
    private String spaceNamesJson;
}
