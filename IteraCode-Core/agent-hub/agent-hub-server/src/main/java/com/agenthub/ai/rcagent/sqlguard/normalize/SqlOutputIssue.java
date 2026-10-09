package com.agenthub.ai.rcagent.sqlguard.normalize;

/**
 * RCAgent SQL 输出失败分类（L3，内部流程控制信号，**非 HTTP 错误码 / 非 SSE 事件值**）。
 *
 * <p>语义对齐 spec.md A.3 决策表与 data-model.md 5.1：用于区分「需回喂重生成」与「需交由 sqlguard 拦截」，
 * 只影响是否触发输出修复回流（L4），不改变对外响应与 SSE 契约。</p>
 *
 * <p><b>禁止</b>：作为 `BaseResponse.code`、HTTP 响应字段或 SSE 事件值；允许作为应用日志分类标签，
 * 但禁止与 SQL 全文同时出现（AGENTS.md 5.1/5.4）。</p>
 */
public enum SqlOutputIssue {

    /** 成功归一为可解析的只读单语句 SQL */
    NONE,

    /** 原始输出为 null / 空白 / 长度 0 */
    EMPTY,

    /** 文本中不存在任何可解析为只读 SQL 的片段（纯散文 / 无 sql 字段的 JSON / 工具调用文本） */
    NOT_SQL,

    /** 存在 SQL 起点片段但解析失败（语法 / 方言错误） */
    SYNTAX_ERROR,

    /** 解析成功但非只读（DML / DDL / 事务），不放行归一，交 sqlguard 按既有策略 BLOCK */
    NOT_READONLY,

    /** 解析为多语句且无法剥离为单语句 */
    MULTI,

    /** 语法 / 只读通过但 schema 预检表 / 字段不存在（由既有 SCHEMA_FIX 回流处理） */
    UNKNOWN_TABLE;

    /**
     * 是否属于「输出格式修复回流（L4）」可重生成类。
     *
     * <p>{@code EMPTY/NOT_SQL/SYNTAX_ERROR/MULTI} 禁止直接 BLOCK，先回喂结构化错误重生成；
     * {@code NOT_READONLY} 重生成无益且属安全边界，{@code UNKNOWN_TABLE} 走既有 schema 回流，均不在此列。</p>
     */
    public boolean isOutputFixCandidate() {
        return this == EMPTY || this == NOT_SQL || this == SYNTAX_ERROR || this == MULTI;
    }

    /**
     * 是否允许由 sqlguard 按既有策略直接判 {@code risk_level=BLOCK}（安全策略不放宽）。
     */
    public boolean allowsDirectBlock() {
        return this == NOT_READONLY;
    }
}
