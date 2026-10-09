package com.agenthub.ai.rcagent.kbcrawler.vo;

import lombok.Data;

/**
 * 接口4 暂停采集任务响应。
 */
@Data
public class PauseResultVO {

    /** 本次暂停任务数 */
    private Integer pausedCount;

    /** 运行中被忽略（继续跑完）的任务数 */
    private Integer runningIgnored;

    public PauseResultVO() {
    }

    public PauseResultVO(Integer pausedCount, Integer runningIgnored) {
        this.pausedCount = pausedCount;
        this.runningIgnored = runningIgnored;
    }
}
