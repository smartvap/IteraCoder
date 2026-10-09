package com.agenthub.ai.rcagent.kbcrawler.model;

import lombok.Data;

/**
 * 变更 diff（kb_crawl_change_diff，不落库/结果）。
 */
@Data
public class ChangeDetectDiff {

    /** 数据源 */
    private String dsId;

    /** 变更类型：TABLE_ADD/TABLE_MODIFY/TABLE_DROP/COLUMN_ADD/COLUMN_DROP/COLUMN_TYPE_CHANGE/STATS_CHANGE */
    private String changeType;

    /** 影响表 */
    private String targetTable;

    /** 影响列（列级变更时） */
    private String targetColumn;

    /** 处置动作：insert/update/deprecate/refresh_stats */
    private String action;

    /** 是否需结构变更影响分析（结构相关为 1：联动 module-004 confidence ×0.5 → needs_review） */
    private Integer structureImpact;

    /** 证据（last_ddl_time/columns_hash 变化摘要） */
    private String evidence;
}
