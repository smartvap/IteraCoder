package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 五级验证单级结果（ValidationReport.levels）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LevelResult {

    /** 等级标识：L1 Syntax / L2 Schema / L3 Semantic / L4 Execution / L5 Result */
    private String level;

    /** 结果：PASS/FAIL/WARN/SKIP */
    private String result;

    /** 详情（可含修复动作提示，不含 SQL 原文与敏感字面量） */
    private String detail;

    /** 失败/警告原因摘要 */
    private String reason;
}
