package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 五级验证报告（data-model.md §5 ValidationReport，SPI S-2/S-5 出参）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationReport {

    /** 五级结果：[{level, result(PASS/FAIL/WARN/SKIP), detail, reason}] */
    @Builder.Default
    private List<LevelResult> levels = new ArrayList<>();

    /** 整体是否通过（L1/L2/L4 FAIL 即 false；L3/L5 异常回退；L3 WARN 不阻断） */
    private Boolean passed;

    /** 关联分级结果 */
    private RiskAssessment risk;

    /** 结构化修复建议 */
    @Builder.Default
    private List<FixSuggestion> fixSuggestions = new ArrayList<>();

    /** 语义尽力校验警告（L3 WARN 提示靠 Result/反馈兜底） */
    private String warning;
}
