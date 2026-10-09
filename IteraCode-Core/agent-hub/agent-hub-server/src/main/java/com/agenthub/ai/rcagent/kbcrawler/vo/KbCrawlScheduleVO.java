package com.agenthub.ai.rcagent.kbcrawler.vo;

import lombok.Data;

/**
 * 采集调度配置响应（查询/修改接口共用，接口1/2）。
 */
@Data
public class KbCrawlScheduleVO {

    /** 数据源实例 ID */
    private String dsId;

    /** 固定 SCHEMA_INCR */
    private String taskType;

    /** 任务行 ID（任务行不存在时为 null） */
    private Long taskId;

    /** 数据库原始值（null/"" 表示使用默认表达式） */
    private String scheduleCron;

    /** 实际生效的 cron（scheduleCron 为空时为配置默认表达式） */
    private String effectiveCron;

    /** 配置默认表达式（agenthub.kbcrawler.scheduler.default-cron） */
    private String defaultCron;

    /** 下次到点时间（yyyy-MM-dd HH:mm:ss，未建立基线时为 null） */
    private String nextRunTime;

    /** 任务状态：scheduled/running/success/failed（任务行不存在为 null） */
    private String status;

    /** 暂停标记 0/1（任务行不存在为 null） */
    private Integer pauseFlag;

    /** 最近运行时间（yyyy-MM-dd HH:mm:ss，可空） */
    private String lastRunTime;

    /** 累计运行次数（任务行不存在为 null） */
    private Integer runCount;

    /** 是否已登记任务行 */
    private boolean registered;
}
