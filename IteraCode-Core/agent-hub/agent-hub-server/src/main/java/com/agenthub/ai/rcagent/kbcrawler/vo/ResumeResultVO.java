package com.agenthub.ai.rcagent.kbcrawler.vo;

import lombok.Data;

/**
 * 接口5 恢复采集任务响应。
 */
@Data
public class ResumeResultVO {

    /** 本次恢复任务数 */
    private Integer resumedCount;

    public ResumeResultVO() {
    }

    public ResumeResultVO(Integer resumedCount) {
        this.resumedCount = resumedCount;
    }
}
