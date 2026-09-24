package com.agenthub.ai.dbaccess.config;

import com.agenthub.ai.dbaccess.model.ResourceLimits;
import com.agenthub.ai.dbaccess.model.SensitiveFieldRule;
import com.agenthub.ai.dbaccess.model.SensitivePolicy;
import com.agenthub.ai.dbaccess.model.WhitelistRule;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * db-access-common 模块配置（前缀 agenthub.dbaccess）。
 *
 * <p>限制默认值（maxRows/maxSeconds/敏感策略/allowSelectStar）一律经此处注入，代码禁止硬编码 1000/10 魔法值。
 * 密码仅以 ${ENV} 引用出现，永不承载明文。</p>
 *
 * <pre>
 * agenthub:
 *   dbaccess:
 *     default-limits:
 *       max-rows: 1000
 *       max-seconds: 10
 *       sensitive-policy: DENY
 *       allow-select-star: false
 *     datasources:
 *       - ds-id: "mysql-local-poc"
 *         ds-name: "本地MySQL POC"
 *         db-type: "MYSQL"
 *         jdbc-url: "jdbc:mysql://${DBACCESS_MYSQL_HOST}:3306/${DBACCESS_MYSQL_DB}?useSSL=false"
 *         readonly-user: "${DBACCESS_MYSQL_READONLY_USER}"
 *         readonly-password-ref: "${DBACCESS_MYSQL_READONLY_PWD}"
 *         # 可访问库集合（可选；为空时回落 space-name 单库）：space-names: ["CRM_A","CRM_B"]
 *         # whitelist 未配置(null) → 默认允许全部表；三种 mode 示例：
 *         # ALL：全部放行；ALLOW_ONLY：仅允许列出表(支持 * % 通配)；DENY_ONLY：仅禁止列出表。
 *         whitelist: { "tables": ["CUSTOMER", "CRM_*"], "mode": "ALLOW_ONLY" }
 *         # 显式全拒：whitelist: { "tables": [], "mode": "ALLOW_ONLY" }
 *     sensitive-rules:
 *       - table: "CUSTOMER"
 *         column: "PHONE"
 *         level: HIGH
 * </pre>
 *
 * <p>默认语义（用户确认）：{@code whitelist} 为 null / mode 缺省且 tables 为空时默认允许全部表，
 * 安全由只读账号 + 敏感字段 DENY + SELECT * 限制 + maxRows/maxSeconds 兜底；如需显式全拒，
 * 配置 {@code mode=ALLOW_ONLY} 且 {@code tables=[]}。</p>
 */
@Data
@ConfigurationProperties(prefix = "agenthub.dbaccess")
public class DbAccessProperties {

    /** 模块默认限制 */
    private DefaultLimits defaultLimits = new DefaultLimits();

    /** P0 配置驱动数据源列表（等价于 t_ds_config 行） */
    private List<DataSourceEntry> datasources = new ArrayList<>();

    /** 敏感字段清单（全局规则 dsId 可空；模块默认注入，避免代码硬编码真实样本） */
    private List<SensitiveFieldRule> sensitiveRules = new ArrayList<>();

    /**
     * 将 default-limits 转换为完整 ResourceLimits 模型（数值默认在 DefaultLimits 字段统一声明）。
     */
    public ResourceLimits defaultLimitsModel() {
        return ResourceLimits.builder()
                .maxRows(defaultLimits.getMaxRows())
                .maxSeconds(defaultLimits.getMaxSeconds())
                .sensitivePolicy(defaultLimits.getSensitivePolicy())
                .allowSelectStar(defaultLimits.getAllowSelectStar())
                .build();
    }

    /** 默认限制子配置（缺失字段回落安全默认值） */
    @Data
    public static class DefaultLimits {
        /** 最大返回行数（默认 1000） */
        private Integer maxRows = 1000;

        /** 最大执行秒数（默认 10） */
        private Integer maxSeconds = 10;

        /** 敏感字段策略（默认 DENY，安全基线） */
        private SensitivePolicy sensitivePolicy = SensitivePolicy.DENY;

        /** 是否放行 SELECT *（默认 false） */
        private Boolean allowSelectStar = false;
    }

    /**
     * 数据源注册项（yml 等价 t_ds_config 一行）。
     */
    @Data
    public static class DataSourceEntry {

        /** 数据源标识 */
        private String dsId;

        /** 显示名 */
        private String dsName;

        /** 归属系统编码 */
        private String systemCode;

        /** 库类型（DbType.code） */
        private String dbType;

        /** JDBC 连接串（禁内嵌账密，允许 ${ENV} 占位） */
        private String jdbcUrl;

        /** 驱动类（可空，按 db_type 推断） */
        private String driverClass;

        /** 只读账号（允许 ${ENV} 占位） */
        private String readonlyUser;

        /** 只读密码引用 ${ENV}（禁止明文） */
        private String readonlyPasswordRef;

        /** 属主组 */
        private String ownerGroup;

        /** Schema/Space */
        private String spaceName;

        /**
         * 可访问库集合（MySQL database / Oracle schema / OceanBase tenant.db；yml 可为多库配置，
         * 如 space-names: ["CRM_A","CRM_B"]）。为空/null 时回落 {@link #spaceName} 单库。
         */
        private List<String> spaceNames;

        /** 白名单（null=默认允许全部表；显式配置后按 mode 约束，ALLOW_ONLY/空 tables 可显式全拒） */
        private WhitelistRule whitelist;

        /** 资源限制覆盖项 */
        private ResourceLimits limits;

        /** 备注 */
        private String remark;

        /** 状态（默认 1 启用） */
        private Integer status = 1;
    }
}
