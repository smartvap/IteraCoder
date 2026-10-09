package com.agenthub.ai.rcagent.kbcrawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * kb-schema-crawler 模块配置（前缀 agenthub.kbcrawler）。
 *
 * <pre>
 * agenthub:
 *   kbcrawler:
 *     runtime:
 *       enabled: false            # 运行时采集总开关（默认关闭）
 *       interceptor-enabled: false # MyBatis 拦截器 SDK 开关（默认关闭，接入方启用）
 *       report-batch-size: 200     # 运行时 flush 批量阈值（条数）
 *       flush-interval-ms: 5000    # 运行时 flush 周期
 *       token-header: "X-Kb-Runtime-Token" # runtime 接入方内部 token header 名
 *     mapper:
 *       scan-dirs:                 # mapper 静态解析目录清单（代码仓库快照路径）
 *         - "."
 *     schema:
 *       include-stats: false       # schema 采集是否采样统计（聚合脱敏，默认关闭避免重查询）
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "agenthub.kbcrawler")
public class KbCrawlerProperties {

    /** 运行时采集配置 */
    private Runtime runtime = new Runtime();

    /** Mapper 静态解析目录配置 */
    private Mapper mapper = new Mapper();

    /** Schema 采集配置 */
    private Schema schema = new Schema();

    /** 调度配置 */
    private Scheduler scheduler = new Scheduler();

    @Data
    public static class Runtime {
        /** 运行时采集总开关（默认 false，BR-003） */
        private boolean enabled = false;

        /** MyBatis 拦截器 SDK 开关（默认 false；需接入方配置后开启并指向上报端点） */
        private boolean interceptorEnabled = false;

        /** flush 批量阈值（内存聚类达到该条数批量落库） */
        private int reportBatchSize = 200;

        /** flush 周期 ms（达到该时间批量落库） */
        private long flushIntervalMs = 5000L;

        /** runtime 接入方内部 token header 名（MVP 由接入方网关校验，不硬编码值） */
        private String tokenHeader = "X-Kb-Runtime-Token";
    }

    @Data
    public static class Mapper {
        /** Mapper XML / DAO 代码快照目录清单（本地路径或 Git 检出目录） */
        private List<String> scanDirs = new ArrayList<>();
    }

    @Data
    public static class Schema {
        /** Schema 采集是否发起统计采样（聚合脱敏；默认 false 避免重查询） */
        private boolean includeStats = false;

        /** 无采集基线的库本次是否按全量采集（true=无基线库首采全量；false=恢复纯增量旧行为） */
        private boolean autoFullWhenNoBaseline = true;
    }

    @Data
    public static class Scheduler {
        /** 定时调度总开关（默认开启，受 kb_crawl_task.pause_flag 控制） */
        private boolean enabled = true;

        /** 调度扫描周期 ms */
        private long fixedDelayMs = 60000L;

        /**
         * 同任务触发最小间隔下限 ms（默认 0 = 频率完全由 cron 决定）。
         *
         * <p>语义：由旧版「唯一频率门槛」调整为「同任务触发最小间隔下限」，
         * 恢复 300000L 即恢复旧版"最小 5 分钟"限流。</p>
         */
        private long minIntervalMs = 0L;

        /** 任务行 schedule_cron 为空时的默认表达式（Spring 6 段：秒 分 时 日 月 周；默认每 60 秒） */
        private String defaultCron = "0 * * * * ?";
    }

    /** runtime 通道是否可用（总开关打开即视为启用） */
    public boolean runtimeEnabled() {
        return runtime != null && runtime.isEnabled();
    }
}
