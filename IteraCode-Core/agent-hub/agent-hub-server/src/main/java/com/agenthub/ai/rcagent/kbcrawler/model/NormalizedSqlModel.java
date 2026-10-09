package com.agenthub.ai.rcagent.kbcrawler.model;

import lombok.Data;

/**
 * SQL 归一化模型（kb_crawl_normalized_sql，不落库）。
 * 参数值占位（?）、大小写折叠、空白折叠、引号/数值字面量脱敏归一。
 */
@Data
public class NormalizedSqlModel {

    /** 数据源 */
    private String dsId;

    /** 原始 SQL（仅本地流转，日志截断） */
    private String rawSql;

    /** 归一化 SQL（含占位符，无参数明细） */
    private String normalizedSql;

    /** 归一化 SQL SHA-256 指纹 */
    private String sqlFingerprint;

    /** 涉及表（简单 FROM/JOIN 提取） */
    private java.util.List<String> involvedTables;

    /** 库类型 */
    private String dbType;

    /** 推断业务动作（biz_action，来源于 biz_context.service/dao 或表名+语句类型） */
    private String bizAction;

    /** 归一化是否被拒（含敏感字面量/空） */
    private boolean rejected;

    /** 拒收原因 */
    private String rejectReason;
}
