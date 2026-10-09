package com.agenthub.ai.rcagent.kbcrawler.vo;

import lombok.Data;

/**
 * 接口1 手动触发采集任务响应。
 */
@Data
public class CrawlTriggerResultVO {

    /** kb_crawl_task.id */
    private Long taskId;

    /** 数据源 */
    private String dsId;

    /** 采集类型 */
    private String taskType;

    /** running */
    private String status;

    /** 本次触发摘要（任务完成后的统计写入任务行，此处返回空或提示） */
    private Object summary;
}
