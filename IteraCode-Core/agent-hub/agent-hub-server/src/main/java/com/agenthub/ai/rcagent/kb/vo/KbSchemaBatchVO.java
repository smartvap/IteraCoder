package com.agenthub.ai.rcagent.kb.vo;

import lombok.Data;

import java.util.List;

/**
 * Schema 批量同步结果 VO（接口8）。
 */
@Data
public class KbSchemaBatchVO {

    /** 成功保存表数 */
    private Long savedTables;

    /** 成功保存列数 */
    private Long savedColumns;

    /** 本次落库的索引条数（入参未提供 indexes 时为 0） */
    private Long savedIndexes;

    /** full_sync 模式下未再出现的表置 deprecated 数 */
    private Long deprecatedTables;

    /** 变更摘要 */
    private List<KbChangeSummaryVO> changes;
}
