package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构化修复建议（ValidationReport.fixSuggestions）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixSuggestion {

    /** 动作：ADD_LIMIT/FIX_TABLE/FIX_COLUMN/FIX_JOIN/RESELECT/SQL_ERROR/ADD_FILTER */
    private String action;

    /** 修复对象（表名/字段名/join 描述，可空） */
    private String target;

    /** 修复建议消息（中文，供 workflow 回退重新生成消费） */
    private String message;
}
