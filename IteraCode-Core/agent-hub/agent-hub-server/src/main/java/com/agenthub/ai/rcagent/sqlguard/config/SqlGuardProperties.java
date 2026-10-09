package com.agenthub.ai.rcagent.sqlguard.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * sqlguard 模块配置（前缀 agenthub.sqlguard）。
 *
 * <pre>
 * agenthub:
 *   sqlguard:
 *     debug-enabled: true          # 调试端点开关（生产建议 false）
 *     kb-base-url: http://localhost:8989/api/v1   # module-004 kb 检索基址（本服务自带检索端点）
 *     kb-timeout-ms: 3000
 *     fallback:
 *       max-rows: 1000             # 策略缺失时默认行数兜底（限制仍受 db-access-common ds limits 交集约束）
 *       max-seconds: 10
 *       sensitive-policy: DENY
 *       select-star-policy: BLOCK
 *     rule:
 *       big-join-count: 3          # 多表大 JOIN 判定阈值（HIGH）
 *       complex-where-count: 3     # 复杂条件判定阈值（MEDIUM）
 *       big-table-rows: 1000000    # 大表行数估计阈值（HIGH 全扫参考）
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agenthub.sqlguard")
public class SqlGuardProperties {

    /** 调试端点开关（默认 true；生产建议配置 false） */
    private boolean debugEnabled = true;

    /** kb 检索服务基址（module-004，默认本服务自带 /api/v1 前缀） */
    private String kbBaseUrl = "http://localhost:8989/api/v1";

    /** kb 检索超时 ms */
    private long kbTimeoutMs = 3000;

    /** 策略缺失/兜底配置（GLOBAL seed 未初始化或未查到时的安全默认） */
    private Fallback fallback = new Fallback();

    /** 分级规则阈值配置（从配置读取，禁止代码硬编码阈值） */
    private Rule rule = new Rule();

    /** 兜底限制 */
    @Data
    public static class Fallback {
        /** 默认行数 */
        private Integer maxRows = 1000;
        /** 默认秒数 */
        private Integer maxSeconds = 10;
        /** 默认敏感策略 */
        private String sensitivePolicy = "DENY";
        /** 默认 SELECT * 策略 */
        private String selectStarPolicy = "BLOCK";
    }

    /** 分级规则阈值 */
    @Data
    public static class Rule {
        /** 多表大 JOIN 判定阈值（joinCount >= 值 且无行级过滤 → HIGH） */
        private int bigJoinCount = 3;
        /** 复杂条件判定阈值（whereCount >= 值 → MEDIUM） */
        private int complexWhereCount = 3;
        /** 大表行数估计阈值（单表无 where/limit 且行数估计 >= 值 → HIGH 全扫风险） */
        private long bigTableRows = 1_000_000L;
    }
}
