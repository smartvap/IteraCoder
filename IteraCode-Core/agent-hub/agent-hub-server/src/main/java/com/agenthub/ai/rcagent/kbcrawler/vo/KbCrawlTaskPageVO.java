package com.agenthub.ai.rcagent.kbcrawler.vo;

import lombok.Data;

import java.util.List;

/**
 * 接口3 采集任务分页列表响应（records 元素同 KbCrawlTaskStatusVO）。
 */
@Data
public class KbCrawlTaskPageVO {

    /** 总条数 */
    private Long total;

    /** 当前页数据 */
    private List<KbCrawlTaskStatusVO> records;
}
