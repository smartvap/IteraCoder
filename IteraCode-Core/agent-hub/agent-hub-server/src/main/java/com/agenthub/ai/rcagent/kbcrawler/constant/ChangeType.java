package com.agenthub.ai.rcagent.kbcrawler.constant;

/**
 * 变更类型（data-model 数据字典 ChangeType）。
 */
public enum ChangeType {

    /** 新增表 */
    TABLE_ADD("TABLE_ADD"),
    /** 表注释/统计变化或表级修改 */
    TABLE_MODIFY("TABLE_MODIFY"),
    /** 表消失（删除） */
    TABLE_DROP("TABLE_DROP"),
    /** 新增列 */
    COLUMN_ADD("COLUMN_ADD"),
    /** 列消失 */
    COLUMN_DROP("COLUMN_DROP"),
    /** 列类型/可空/键变化 */
    COLUMN_TYPE_CHANGE("COLUMN_TYPE_CHANGE"),
    /** 仅统计变化 */
    STATS_CHANGE("STATS_CHANGE");

    private final String value;

    ChangeType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
