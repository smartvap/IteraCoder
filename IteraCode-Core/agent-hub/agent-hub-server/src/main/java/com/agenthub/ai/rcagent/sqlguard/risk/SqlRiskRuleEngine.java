package com.agenthub.ai.rcagent.sqlguard.risk;

import com.agenthub.ai.rcagent.sqlguard.constant.RuleId;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardRiskLevel;
import com.agenthub.ai.rcagent.sqlguard.constant.SqlGuardVerdict;
import com.agenthub.ai.rcagent.sqlguard.model.ColumnRef;
import com.agenthub.ai.rcagent.sqlguard.model.RiskAssessment;
import com.agenthub.ai.rcagent.sqlguard.model.RiskRuleHit;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AST 规则分级引擎（FR-002，纯规则无 LLM）。
 *
 * <p>规则优先级（BR-002/BR-005）：BLOCK（selectStar/writeIntent/未知表）> HIGH（大表全扫/敏感 HIGH/多表大 JOIN）
 * > MEDIUM（JOIN/子查询/复杂条件）> LOW（单表确定查询/简单聚合）。
 * 阈值来自 {@link RiskRuleContext}（策略/元数据/配置注入），禁止硬编码。</p>
 */
@Component
public class SqlRiskRuleEngine {

    /** 分级来源标记 */
    public static final String CLASSIFIED_BY = "AST_RULE";

    /**
     * 纯分级（S-3）：feature + 上下文 → RiskAssessment。
     *
     * @param feature AST 特征
     * @param ctx     阈值上下文（可空，空时使用默认安全值）
     */
    public RiskAssessment classify(SqlFeature feature, RiskRuleContext ctx) {
        RiskRuleContext rule = ctx == null ? RiskRuleContext.defaults() : ctx;
        RiskAssessment assessment = RiskAssessment.builder()
                .rulesHit(new ArrayList<>())
                .classifiedBy(CLASSIFIED_BY)
                .build();
        if (feature == null) {
            assessment.setRiskLevel(SqlGuardRiskLevel.BLOCK.name());
            assessment.setVerdict(SqlGuardVerdict.BLOCK.name());
            assessment.setBlockedReason("缺少可分析的 SQL 特征（AST 为空）");
            return assessment;
        }

        // 1) BLOCK：SELECT *（策略开启时）/ 写形态（策略开启时）
        boolean blockOn = rule.isBlockEnabled();
        if (blockOn && rule.isSelectStarBlock() && Boolean.TRUE.equals(feature.getSelectStar())) {
            assessment.setRiskLevel(SqlGuardRiskLevel.BLOCK.name());
            assessment.setVerdict(SqlGuardVerdict.BLOCK.name());
            assessment.setBlockedReason("SELECT * 禁止（请显式列出查询列）");
            assessment.addHit(hit(RuleId.SELECT_STAR_BLOCK, SqlGuardRiskLevel.BLOCK.name(),
                    "SELECT * / t.* 一律 BLOCK", "selectStar=true"));
            return assessment;
        }
        if (blockOn && Boolean.TRUE.equals(feature.getWriteIntent())) {
            assessment.setRiskLevel(SqlGuardRiskLevel.BLOCK.name());
            assessment.setVerdict(SqlGuardVerdict.BLOCK.name());
            assessment.setBlockedReason("写形态/DDL/多语句禁止（仅允许只读查询）");
            assessment.addHit(hit(RuleId.WRITE_INTENT_BLOCK, SqlGuardRiskLevel.BLOCK.name(),
                    "DML/DDL/事务/多语句按写形态 BLOCK", "writeIntent=true class=" + feature.getStatementClass()));
            return assessment;
        }

        // 1b) BLOCK：未知表（L2 前置已标记，feature.tables 之外再保守判定无意义，此处由外部 ctx 或 L2 直接给出）
        //     作为引擎兜底：表清单为空且语义不可信按 LOW 处理（交给 L2 失败），引擎只做 AST 结构分级。

        // 2) HIGH：敏感字段命中（ctx 提供）
        List<String> sensitive = rule.getSensitiveColumns();
        if (!CollectionUtils.isEmpty(sensitive)) {
            String evidence = String.join(",", sensitive);
            assessment.setRiskLevel(SqlGuardRiskLevel.HIGH.name());
            assessment.setVerdict(SqlGuardVerdict.REVIEW.name());
            assessment.setReviewHint("命中 HIGH 敏感字段，需人工审核确认脱敏/授权");
            assessment.addHit(hit(RuleId.SENSITIVE_FIELD_HIGH, SqlGuardRiskLevel.HIGH.name(),
                    "命中 HIGH 敏感字段（column 摘要，不落原文）", evidence));
            return assessment;
        }

        // 3) HIGH：大表全扫特征（无 WHERE 或大表无 LIMIT）
        int whereCount = feature.getWhereCount() == null ? 0 : feature.getWhereCount();
        boolean hasLimit = Boolean.TRUE.equals(feature.getHasLimit());
        boolean hasWhere = whereCount > 0;
        String fullScanReason = fullScanReason(feature, rule.getBigTableRows(), rule.getTableRowEstimate());
        if (!hasWhere && !hasLimit) {
            assessment.setRiskLevel(SqlGuardRiskLevel.HIGH.name());
            assessment.setVerdict(SqlGuardVerdict.REVIEW.name());
            assessment.setReviewHint("无 WHERE 且无 LIMIT，疑似大表全表扫描");
            assessment.addHit(hit(RuleId.FULL_SCAN_HIGH, SqlGuardRiskLevel.HIGH.name(),
                    "无过滤条件且无行数限制", "whereCount=0 hasLimit=false"));
            return assessment;
        }
        if (!hasLimit && fullScanReason != null) {
            assessment.setRiskLevel(SqlGuardRiskLevel.HIGH.name());
            assessment.setVerdict(SqlGuardVerdict.REVIEW.name());
            assessment.setReviewHint("大表估计行数超阈值且未加 LIMIT，疑似全表扫描");
            assessment.addHit(hit(RuleId.FULL_SCAN_HIGH, SqlGuardRiskLevel.HIGH.name(),
                    "大表无 LIMIT 查询", fullScanReason));
            return assessment;
        }

        // 4) HIGH：多表大 JOIN（joinCount>=阈值 且无行级过滤/无 limit）
        int joinCount = feature.getJoinCount() == null ? 0 : feature.getJoinCount();
        if (joinCount >= rule.getBigJoinCount() && !hasLimit) {
            assessment.setRiskLevel(SqlGuardRiskLevel.HIGH.name());
            assessment.setVerdict(SqlGuardVerdict.REVIEW.name());
            assessment.setReviewHint("多表大 JOIN 且无 LIMIT，建议核对关联条件并限制行数");
            assessment.addHit(hit(RuleId.BIG_JOIN_HIGH, SqlGuardRiskLevel.HIGH.name(),
                    "JOIN 数达阈值且无行数限制", "joinCount=" + joinCount + " hasLimit=false"));
            return assessment;
        }

        // 5) MEDIUM：JOIN / 子查询 / 复杂条件
        boolean subquery = Boolean.TRUE.equals(feature.getHasSubquery());
        if (joinCount >= 1) {
            assessment.setRiskLevel(SqlGuardRiskLevel.MEDIUM.name());
            assessment.setVerdict(SqlGuardVerdict.REVIEW.name());
            assessment.addHit(hit(RuleId.JOIN_MEDIUM, SqlGuardRiskLevel.MEDIUM.name(),
                    "多表 JOIN，需人工审核关联口径", "joinCount=" + joinCount));
            return assessment;
        }
        if (subquery) {
            assessment.setRiskLevel(SqlGuardRiskLevel.MEDIUM.name());
            assessment.setVerdict(SqlGuardVerdict.REVIEW.name());
            assessment.addHit(hit(RuleId.SUBQUERY_MEDIUM, SqlGuardRiskLevel.MEDIUM.name(),
                    "含子查询，需人工审核结果语义", "hasSubquery=true"));
            return assessment;
        }
        if (whereCount >= rule.getComplexWhereCount()) {
            assessment.setRiskLevel(SqlGuardRiskLevel.MEDIUM.name());
            assessment.setVerdict(SqlGuardVerdict.REVIEW.name());
            assessment.addHit(hit(RuleId.COMPLEX_MEDIUM, SqlGuardRiskLevel.MEDIUM.name(),
                    "条件复杂度较高，需人工审核过滤口径", "whereCount=" + whereCount));
            return assessment;
        }

        // 6) LOW：单表确定查询 / 简单聚合
        assessment.setRiskLevel(SqlGuardRiskLevel.LOW.name());
        assessment.setVerdict(SqlGuardVerdict.ALLOW.name());
        assessment.addHit(hit(RuleId.SIMPLE_QUERY_LOW, SqlGuardRiskLevel.LOW.name(),
                "单表确定查询/简单聚合，自动放行只读执行",
                "joinCount=" + joinCount + " subquery=" + subquery + " whereCount=" + whereCount));
        return assessment;
    }

    private String fullScanReason(SqlFeature feature, long bigTableRows, Map<String, Long> rowEstimate) {
        if (bigTableRows <= 0 || rowEstimate == null || rowEstimate.isEmpty()) {
            return null;
        }
        List<String> bigTables = new ArrayList<>();
        for (String table : feature.tableNames()) {
            if (table == null) {
                continue;
            }
            Long rows = rowEstimate.get(table.trim().toUpperCase(Locale.ROOT));
            if (rows != null && rows >= bigTableRows) {
                bigTables.add(table + "(" + rows + ")");
            }
        }
        return bigTables.isEmpty() ? null : "估计行数超阈值: " + String.join(",", bigTables);
    }

    private RiskRuleHit hit(String ruleId, String level, String reason, String evidence) {
        return RiskRuleHit.builder().ruleId(ruleId).level(level).reason(reason).evidence(evidence).build();
    }
}
