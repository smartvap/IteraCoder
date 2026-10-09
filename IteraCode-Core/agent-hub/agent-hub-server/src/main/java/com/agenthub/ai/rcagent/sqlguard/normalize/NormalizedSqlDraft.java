package com.agenthub.ai.rcagent.sqlguard.normalize;

import lombok.Getter;

/**
 * RCAgent SQL 归一结果载体（L2 出参，内存态，不落库）。
 *
 * <p>契约（api-contract.md 5.1.2 / data-model.md 3.1）：字段不可为 null，{@code sql} 未提取到时为<b>空串</b>；
 * 本对象为 {@link SqlDraftNormalizer#normalize} 的返回值，最终收敛写入图 State 键 {@code sql_generate}
 * （仅承载纯 SQL 或空串）。</p>
 */
@Getter
public class NormalizedSqlDraft {

    /** 归一后纯 SQL（无 Markdown 围栏、无结尾分号）；未提取到为 ""，禁止 null */
    private final String sql;

    /** 失败分类（成功为 {@link SqlOutputIssue#NONE}），禁止 null */
    private final SqlOutputIssue issue;

    /** 命中的提取层码：S0~S5 / NONE（脱敏可观测字段） */
    private final String extractLayer;

    /** 原始输出长度（脱敏可观测字段，不落库，禁止记录 SQL 全文） */
    private final int rawLength;

    public NormalizedSqlDraft(String sql, SqlOutputIssue issue, String extractLayer, int rawLength) {
        this.sql = sql == null ? "" : sql;
        this.issue = issue == null ? SqlOutputIssue.NOT_SQL : issue;
        this.extractLayer = (extractLayer == null || extractLayer.isBlank())
                ? SqlDraftNormalizer.LAYER_NONE : extractLayer;
        this.rawLength = Math.max(0, rawLength);
    }

    /** 静态工厂（语义同构造器，便于链式阅读） */
    public static NormalizedSqlDraft of(String sql, SqlOutputIssue issue, String extractLayer, int rawLength) {
        return new NormalizedSqlDraft(sql, issue, extractLayer, rawLength);
    }

    /** 是否为「可用于下游 sqlguard」的有效草案（issue=NONE 且 sql 非空白） */
    public boolean isUsable() {
        return issue == SqlOutputIssue.NONE && sql != null && !sql.isBlank();
    }
}
