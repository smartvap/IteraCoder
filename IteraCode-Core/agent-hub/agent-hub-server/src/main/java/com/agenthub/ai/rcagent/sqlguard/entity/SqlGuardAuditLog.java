package com.agenthub.ai.rcagent.sqlguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 验证审计流水实体（sql_guard_audit_log，data-model.md §2）。
 *
 * <p>安全约束：本表只存 sql_fingerprint + sql_abstract（占位符化）与风险结论，
 * 禁止存 SQL 原文与敏感字面量。</p>
 */
@Data
@TableName(value = "sql_guard_audit_log")
public class SqlGuardAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源实例 ID */
    private String dsId;

    /** 关联工作流会话 thread_id（可追溯） */
    private String threadId;

    /** 归一化 SQL 的 SHA-256（HEX） */
    private String sqlFingerprint;

    /** SQL 抽象摘要（归一化+占位符化，无字面量） */
    private String sqlAbstract;

    /** 语句类型分类（SELECT/DML/DDL/TRANSACTION/MULTI/UNKNOWN） */
    private String statementType;

    /** 目标库类型 */
    private String dbType;

    /** 涉及表名 JSON 数组（仅表名） */
    private String involvedTables;

    /** 风险等级：BLOCK/LOW/MEDIUM/HIGH */
    private String riskLevel;

    /** 处置结论：ALLOW / REVIEW / BLOCK */
    private String verdict;

    /** BLOCK/异常原因摘要（不落 SQL 原文） */
    private String blockReason;

    private String levelL1Result;

    private String levelL2Result;

    private String levelL3Result;

    private String levelL4Result;

    private String levelL5Result;

    /** 各级结果摘要 JSON（每级 result/reason/建议动作，截断文本） */
    private String levelsSummary;

    /** 修复建议摘要 JSON 数组 */
    private String fixSuggestion;

    /** 解析+分级+验证耗时（ms） */
    private Long costMs;

    /** 仅 executeGuarded：实际返回行数 */
    private Integer resultRowCount;

    /** 仅 executeGuarded：是否截断 */
    private Integer truncated;

    /** 审计状态：VALIDATED / EXECUTED / REJECTED / TIMEOUT */
    private String guardStatus;

    private Date createTime;

    private Date updateTime;

    /** 逻辑删除 0/1 */
    private Integer isDeleted;
}
