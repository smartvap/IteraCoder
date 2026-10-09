package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 风险分级结果（data-model.md §4 RiskAssessment，SPI S-2/S-3 出参）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAssessment {

    /** BLOCK / LOW / MEDIUM / HIGH */
    private String riskLevel;

    /** 处置结论：ALLOW（LOW 自动）/ REVIEW（MED/HIGH 人工）/ BLOCK（禁止） */
    private String verdict;

    /** 命中规则明细（供"为什么拦/为什么审"） */
    @Builder.Default
    private List<RiskRuleHit> rulesHit = new ArrayList<>();

    /** BLOCK 原因摘要（SELECT * / DML DDL / 未知表等，不落 SQL 原文） */
    private String blockedReason;

    /** 转人工提示（HIGH：大表全扫/敏感字段/多表大 JOIN 等） */
    private String reviewHint;

    /** 判定来源：AST_RULE（默认）/ OVERRIDE */
    private String classifiedBy;

    public boolean isBlock() {
        return "BLOCK".equalsIgnoreCase(riskLevel);
    }

    public boolean isLow() {
        return "LOW".equalsIgnoreCase(riskLevel);
    }

    public boolean isMediumOrHigh() {
        return "MEDIUM".equalsIgnoreCase(riskLevel) || "HIGH".equalsIgnoreCase(riskLevel);
    }

    public void addHit(RiskRuleHit hit) {
        if (rulesHit == null) {
            rulesHit = new ArrayList<>();
        }
        rulesHit.add(hit);
    }
}
