package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SPI S-2 validate 入参（api-contract SqlGuardRequest）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlGuardRequest {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 候选只读 SQL（必填，DML/DDL 传入即识别写形态 BLOCK） */
    private String sql;

    /** 目标库类型（缺省从 ds_id 解析） */
    private String dbType;

    /** SQL 规划 Plan（L3 对照；缺省跳过 L3 为 SKIP 并提示） */
    private SqlPlanView plan;

    /** 行数限制（验证 explain 时用，可空=策略默认） */
    private Integer maxRows;

    /** 时间限制（秒，可空=策略默认） */
    private Integer maxSeconds;

    /** true 时 L4 仅执行 EXPLAIN 计划不返回业务数据 */
    private Boolean explainOnly;

    /** 操作人（审计，可空默认读 BaseContext） */
    private Long operatorId;

    /** 关联工作流会话 thread_id（审计可追溯，缺省 null） */
    private String threadId;
}
