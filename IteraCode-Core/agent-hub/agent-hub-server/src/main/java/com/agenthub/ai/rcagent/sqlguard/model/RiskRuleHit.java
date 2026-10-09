package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 命中规则明细（RiskAssessment.rulesHit）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleHit {

    /** 规则标识（SELECT_STAR_BLOCK/WRITE_INTENT_BLOCK/FULL_SCAN_HIGH 等） */
    private String ruleId;

    /** 规则判定风险等级（BLOCK/LOW/MEDIUM/HIGH） */
    private String level;

    /** 命中原因（中文摘要，供"为什么拦/为什么审"） */
    private String reason;

    /** 证据（AST 特征摘要，如 joinCount=3 / whereCount=0 / 涉及表 xxx；不含数据字面量） */
    private String evidence;
}
