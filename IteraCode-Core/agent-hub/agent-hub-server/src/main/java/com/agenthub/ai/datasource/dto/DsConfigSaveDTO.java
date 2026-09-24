package com.agenthub.ai.datasource.dto;

import com.agenthub.ai.dbaccess.model.ResourceLimits;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import lombok.Data;

import java.util.List;

/**
 * 数据源注册/编辑入参（api-contract DsConfigSaveDTO）。
 *
 * <p>凭据约束：readonlyPasswordRef 必须是 ${ENV} 形式引用（禁明文）；jdbcUrl 禁止内嵌账密；
 * whitelist/limits 为对象 JSON，落库时序列化为 whitelist_json/limits_json。</p>
 */
@Data
public class DsConfigSaveDTO {

    /** 数据源标识（≤64，格式 ^[a-z0-9][a-z0-9-_]*$，注册后不可变更 BR-003） */
    private String dsId;

    /** 显示名（必填） */
    private String dsName;

    /** 归属系统编码（CRM/OA/...） */
    private String systemCode;

    /** 库类型（必填）：MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE */
    private String dbType;

    /** JDBC 连接串（必填，禁内嵌账密） */
    private String jdbcUrl;

    /** 驱动类名（可空，按 db_type 推断） */
    private String driverClass;

    /** 只读账号用户名（必填 BR-001） */
    private String readonlyUser;

    /** 密码引用 ${ENV}（必填 BR-001，禁明文） */
    private String readonlyPasswordRef;

    /** 属主组 */
    private String ownerGroup;

    /** llm-wiki 知识空间名（可选） */
    private String spaceName;

    /**
     * 可访问库集合（可选；为空/null 时回落 spaceName 单库）。
     * 落库序列化为 space_names JSON，如 ["CRM_A","CRM_B"]。
     */
    private List<String> spaceNames;

    /** 白名单（可选；缺省=默认拒绝全部） */
    private WhitelistRule whitelist;

    /** 资源限制覆盖（可选） */
    private ResourceLimits limits;

    /** 备注 */
    private String remark;

    /** 是否自动联动 llm-wiki 创建/确认空间（默认 false） */
    private Boolean autoCreateSpace;

    /** 乐观锁版本（编辑时回填，冲突返回 43003） */
    private Integer version;
}
