package com.agenthub.ai.dbaccess.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据源脱敏配置视图（SPI 出参，api-contract DsConfigView）。
 *
 * <p>{@code readonlyPasswordRef} 仅输出 ${ENV} 引用形式，<b>永不输出解析后的明文密码</b>。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DsConfigView {

    /** 数据源标识 */
    private String dsId;

    /** 显示名 */
    private String dsName;

    /** 归属系统编码 */
    private String systemCode;

    /** 库类型（DbType.code 字符串） */
    private String dbType;

    /** 连接串（已验证不含内嵌账密） */
    private String jdbcUrl;

    /** 只读账号名 */
    private String readonlyUser;

    /** 只读密码引用（${ENV} 形式，非明文） */
    private String readonlyPasswordRef;

    /** 属主组 */
    private String ownerGroup;

    /** Schema/Space */
    private String spaceName;

    /**
     * 可访问库集合（多库边界；为空/null 时回落 {@link #spaceName} 单库）。
     * 对应 t_ds_config.space_names JSON 列。
     */
    private List<String> spaceNames;

    /** 白名单 */
    private WhitelistRule whitelist;

    /** 限制覆盖 */
    private ResourceLimits limits;

    /** 状态 1/0 */
    private Integer status;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
