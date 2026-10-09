package com.agenthub.ai.rcagent.kbcrawler.vo;

import lombok.Data;

/**
 * 接口2 采集任务状态响应（单条）。
 */
@Data
public class KbCrawlTaskStatusVO {

    /** 数据源 */
    private String dsId;

    /** 采集类型 */
    private String taskType;

    /** 任务显示名 */
    private String taskName;

    /** scheduled/running/success/failed */
    private String status;

    /** 1=暂停 */
    private Integer pauseFlag;

    /** 最近运行时间 */
    private String lastRunTime;

    /** success/failed/running */
    private String lastResult;

    /** 失败摘要（截断） */
    private String lastError;

    /** 累计运行次数 */
    private Integer runCount;

    /** 备注/降级说明 */
    private String remark;
}
