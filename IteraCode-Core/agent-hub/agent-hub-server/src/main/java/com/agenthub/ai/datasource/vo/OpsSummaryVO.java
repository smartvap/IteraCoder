package com.agenthub.ai.datasource.vo;

import lombok.Data;

/**
 * 数据源视角运维概况（api-contract OpsSummaryVO，FR-005 可选 P2）。
 *
 * <p>module-003/004/008 未接入时返回空对象 + degradation 提示，不抛错。</p>
 */
@Data
public class OpsSummaryVO {

    private String dsId;

    /** 最近采集状态（module-003 kb-schema-crawler 占位） */
    private OpsCrawlVO lastCrawl;

    /** 待审核任务数（module-004 占位） */
    private Integer pendingReviewCount;

    /** 反馈量（module-008 占位） */
    private Integer feedbackCount;

    /** 依赖模块未接入提示 */
    private String degradation;

    /** 最近采集对象（module-003 契约占位） */
    @Data
    public static class OpsCrawlVO {

        private String taskType;

        private String status;

        private String lastRunTime;

        private String lastResult;
    }
}
