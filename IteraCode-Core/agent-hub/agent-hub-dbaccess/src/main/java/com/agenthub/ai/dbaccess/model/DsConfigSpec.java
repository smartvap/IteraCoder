package com.agenthub.ai.dbaccess.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据源注册入参（DsConfigSpec，供 module-009 multi-datasource-admin 调用 register）。
 *
 * <p>凭据约束：{@code readonlyPasswordRef} 必须是 ${ENV} 形式引用；{@code jdbcUrl} 禁止内嵌账密。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DsConfigSpec {

    /** 唯一标识（必填，≤64，格式 ^[a-z0-9][a-z0-9-_]*$） */
    private String dsId;

    /** 显示名（必填） */
    private String dsName;

    /** 系统编码（可空） */
    private String systemCode;

    /** 库类型（必填） */
    private DbType dbType;

    /** 连接串（必填，禁内嵌账密） */
    private String jdbcUrl;

    /** 驱动类（可空，按 db_type 推断） */
    private String driverClass;

    /** 只读账号（必填） */
    private String readonlyUser;

    /** 密码 ${ENV} 引用（必填） */
    private String readonlyPasswordRef;

    /** 属主组（可空） */
    private String ownerGroup;

    /** Schema（可空） */
    private String spaceName;

    /**
     * 可访问库集合（可空；为空/null 时回落 {@link #spaceName} 单库）。
     * 对应 t_ds_config.space_names JSON 列，如 ["CRM_A","CRM_B"]。
     */
    private List<String> spaceNames;

    /** 白名单（可空 = 默认拒绝全部） */
    private WhitelistRule whitelist;

    /** 限制覆盖（可空） */
    private ResourceLimits limits;

    /** 备注（可空） */
    private String remark;

    /**
     * 转换为待持久化 DsConfig（默认启用、乐观锁版本 0、时间戳当前）。
     */
    public DsConfig toConfig() {
        LocalDateTime now = LocalDateTime.now();
        return DsConfig.builder()
                .dsId(dsId)
                .dsName(dsName)
                .systemCode(systemCode)
                .dbType(dbType)
                .jdbcUrl(jdbcUrl)
                .driverClass(driverClass)
                .readonlyUser(readonlyUser)
                .readonlyPasswordRef(readonlyPasswordRef)
                .ownerGroup(ownerGroup)
                .spaceName(spaceName)
                .spaceNames(spaceNames)
                .whitelist(whitelist)
                .limits(limits)
                .status(DsStatus.ENABLED.getCode())
                .remark(remark)
                .version(0)
                .createTime(now)
                .updateTime(now)
                .isDeleted(0)
                .build();
    }
}
