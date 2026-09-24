package com.agenthub.ai.dbaccess.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据源注册表核心模型（data-model.md t_ds_config，本模块只读视角 + register SPI 写入口）。
 *
 * <p>凭据约束（BR-003）：本对象只持有 {@code readonlyPasswordRef}（形如 ${ENV}）引用，
 * <b>永不承载明文密码</b>；jdbcUrl 禁止内嵌账密。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DsConfig {

    /** 物理主键（落库模式使用） */
    private Long id;

    /** 全局唯一数据源标识（≤64） */
    private String dsId;

    /** 显示名 */
    private String dsName;

    /** 归属系统编码（CRM/OA/...） */
    private String systemCode;

    /** 库类型 */
    private DbType dbType;

    /** JDBC 连接串（禁止内嵌账密） */
    private String jdbcUrl;

    /** JDBC 驱动类名（可空，按 db_type 推断） */
    private String driverClass;

    /** 只读账号用户名（仅授 SELECT/字典查询） */
    private String readonlyUser;

    /** 只读账号密码引用，形如 ${DBACCESS_DS_XXX_PWD}（运行期由环境变量注入，永不存明文） */
    private String readonlyPasswordRef;

    /** 属主组 / 责任团队 */
    private String ownerGroup;

    /** Schema/Space 名（Oracle 用户空间 / MySQL database / OceanBase tenant.db） */
    private String spaceName;

    /**
     * 可访问库集合（MySQL database / Oracle schema / OceanBase tenant.db）。
     *
     * <p>兼容语义：spaceNames 非空时采集/查询以它为库集合边界；为空/null 时回落 {@link #spaceName}（旧行为，单库）。
     * 对应 t_ds_config.space_names JSON 列，如 ["CRM_A","CRM_B"]。</p>
     */
    private List<String> spaceNames;

    /** 白名单（fail-closed，空/null 表示默认拒绝全部） */
    private WhitelistRule whitelist;

    /** 资源限制覆盖项（空字段回落模块默认） */
    private ResourceLimits limits;

    /** 状态：1=启用 0=停用 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 乐观锁版本（同 ds_id 覆盖时递增） */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 逻辑删除标记：0=正常 1=删除 */
    private Integer isDeleted;

    /** 是否已启用（status=1 且未逻辑删除） */
    public boolean enabled() {
        return Integer.valueOf(1).equals(status) && !Integer.valueOf(1).equals(isDeleted);
    }

    /** 是否已逻辑删除 */
    public boolean deleted() {
        return Integer.valueOf(1).equals(isDeleted);
    }

    /**
     * 转为对外脱敏视图（不含任何密码/引用解析值）。
     */
    public DsConfigView toView() {
        return DsConfigView.builder()
                .dsId(dsId)
                .dsName(dsName)
                .systemCode(systemCode)
                .dbType(dbType == null ? null : dbType.getCode())
                .jdbcUrl(jdbcUrl)
                .readonlyUser(readonlyUser)
                .readonlyPasswordRef(readonlyPasswordRef)
                .ownerGroup(ownerGroup)
                .spaceName(spaceName)
                .spaceNames(spaceNames)
                .whitelist(whitelist)
                .limits(limits)
                .status(status)
                .remark(remark)
                .createTime(createTime)
                .updateTime(updateTime)
                .build();
    }
}
