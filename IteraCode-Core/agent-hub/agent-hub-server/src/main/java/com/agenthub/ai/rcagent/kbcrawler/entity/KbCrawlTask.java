package com.agenthub.ai.rcagent.kbcrawler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 采集任务状态（kb_crawl_task）：一个数据源的一个采集类型对应一行。
 */
@Data
@TableName(value = "kb_crawl_task")
public class KbCrawlTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源实例 ID（module-002 ds_config.ds_id 逻辑引用） */
    private String dsId;

    /** 采集类型：SCHEMA_FULL/SCHEMA_INCR/MAPPER_PARSE/RUNTIME_COLLECT/CHANGE_DETECT */
    private String taskType;

    /** 任务显示名 */
    private String taskName;

    /** 任务状态：scheduled/running/success/failed */
    private String status;

    /** 暂停标记：1=暂停（调度器跳过） */
    private Integer pauseFlag;

    /** 触发来源：MANUAL/SCHEDULE/RELEASE */
    private String triggerType;

    /** 最近一次运行时间 */
    private Date lastRunTime;

    /** 最近一次运行结果：success/failed/running */
    private String lastResult;

    /** 最近失败摘要（截断，禁敏感信息） */
    private String lastError;

    /** 累计运行次数 */
    private Integer runCount;

    /** 备注/降级说明 */
    private String remark;

    /** 任务级调度表达式（Spring 6 段 cron：秒 分 时 日 月 周）；null/空 = 使用配置默认 */
    private String scheduleCron;

    /** 下次到点时间；null = 尚未建立调度基线（首次扫描即视为到点，写回后自愈） */
    private Date nextRunTime;

    private Date createTime;

    private Date updateTime;
}
