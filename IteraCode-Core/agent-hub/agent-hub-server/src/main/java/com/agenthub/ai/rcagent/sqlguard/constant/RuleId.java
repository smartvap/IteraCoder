package com.agenthub.ai.rcagent.sqlguard.constant;

/**
 * 风险规则命中标识（rulesHit.ruleId，供"为什么拦/为什么审"展示与审计）。
 */
public final class RuleId {

    private RuleId() {
    }

    /** SELECT * 一律 BLOCK */
    public static final String SELECT_STAR_BLOCK = "SELECT_STAR_BLOCK";

    /** DML/DDL 写形态 BLOCK */
    public static final String WRITE_INTENT_BLOCK = "WRITE_INTENT_BLOCK";

    /** 未知表（生成不可信）BLOCK */
    public static final String UNKNOWN_TABLE_BLOCK = "UNKNOWN_TABLE_BLOCK";

    /** 敏感字段命中 HIGH */
    public static final String SENSITIVE_FIELD_HIGH = "SENSITIVE_FIELD_HIGH";

    /** 大表全扫特征（无 WHERE 或大表且无 LIMIT） */
    public static final String FULL_SCAN_HIGH = "FULL_SCAN_HIGH";

    /** 多表大 JOIN（JOIN 数达阈值且无行级过滤） */
    public static final String BIG_JOIN_HIGH = "BIG_JOIN_HIGH";

    /** 含 JOIN 或子查询或复杂条件 → MEDIUM */
    public static final String JOIN_MEDIUM = "JOIN_MEDIUM";

    /** 子查询 → MEDIUM */
    public static final String SUBQUERY_MEDIUM = "SUBQUERY_MEDIUM";

    /** 复杂条件（WHERE 条件数超阈值）→ MEDIUM */
    public static final String COMPLEX_MEDIUM = "COMPLEX_MEDIUM";

    /** 单表确定查询 + 显式 LIMIT / 简单聚合 → LOW */
    public static final String SIMPLE_QUERY_LOW = "SIMPLE_QUERY_LOW";

    /** L1 语法解析失败（非风险规则，审计描述用） */
    public static final String SYNTAX_FAILED = "SYNTAX_FAILED";

    /** L3 语义校验不通过（规划-生成不一致）→ 提升人工审核追溯标识（非风险规则，不参与 BLOCK 判定） */
    public static final String PLAN_MISMATCH_REVIEW = "PLAN_MISMATCH_REVIEW";
}
