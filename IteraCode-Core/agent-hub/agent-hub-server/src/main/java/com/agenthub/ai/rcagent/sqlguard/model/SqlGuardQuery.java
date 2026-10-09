package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SPI S-5 受保护只读执行入参（api-contract SqlGuardQuery，data-model §7）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlGuardQuery {

    /** 数据源实例 ID */
    private String dsId;

    /** 待执行 SQL（仅 SELECT，DML/DDL 走 BLOCK） */
    private String sql;

    /** SQL 规划 Plan（L3/L5 对照） */
    private SqlPlanView plan;

    /** 行数覆盖（默认策略∩ds limits） */
    private Integer maxRows;

    /** 秒数覆盖 */
    private Integer maxSeconds;

    /** 人工已审核放行标记（MED/HIGH 经 workflow 审核通过后置 true） */
    private Boolean requireManualApproval;

    /** 跳过 L5 结果检查（调试/explain 场景） */
    private Boolean skipResultCheck;

    /** 操作人（审计） */
    private Long operatorId;

    /** 关联工作流会话 thread_id（审计可追溯，缺省 null） */
    private String threadId;
}
