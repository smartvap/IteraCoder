package com.agenthub.ai.rcagent.sqlguard.constant;

/**
 * 结构化修复建议动作枚举（ValidationReport.fixSuggestions[].action）。
 */
public final class FixAction {

    private FixAction() {
    }

    /** 添加 LIMIT / 行数限制 */
    public static final String ADD_LIMIT = "ADD_LIMIT";

    /** 修正表名 */
    public static final String FIX_TABLE = "FIX_TABLE";

    /** 修正字段名 */
    public static final String FIX_COLUMN = "FIX_COLUMN";

    /** 补 JOIN 条件 / 修正关联字段 */
    public static final String FIX_JOIN = "FIX_JOIN";

    /** 重新选择列（SELECT * 改写为显式列） */
    public static final String RESELECT = "RESELECT";

    /** SQL 语法/方言错误，重写 SQL */
    public static final String SQL_ERROR = "SQL_ERROR";

    /** 补充时间/过滤条件（避免全表/口径漂移） */
    public static final String ADD_FILTER = "ADD_FILTER";
}
