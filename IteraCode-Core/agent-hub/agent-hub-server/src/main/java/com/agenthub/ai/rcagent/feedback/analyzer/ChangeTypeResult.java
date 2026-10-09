package com.agenthub.ai.rcagent.feedback.analyzer;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 修改类型判定结果（FR-BE-002 输出）。
 */
@Data
public class ChangeTypeResult {

    /** 修改类型（ChangeType 值） */
    private String changeType;

    /** 判定置信度 0~1 */
    private BigDecimal confidence;

    /** 是否人工介入（diff 无法解析/LLM 置信过低/无 LLM 兜底） */
    private boolean manualRequired;

    /** 判定依据（evidence，不含 SQL 原文） */
    private String reason;

    public static ChangeTypeResult manual(String reason) {
        ChangeTypeResult result = new ChangeTypeResult();
        result.setChangeType("OTHER");
        result.setConfidence(new BigDecimal("0"));
        result.setManualRequired(true);
        result.setReason(reason);
        return result;
    }
}
