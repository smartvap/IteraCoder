package com.agenthub.ai.rcagent.kbcrawler.model;

import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * SQL 模板聚类（kb_crawl_sql_cluster，不落库/内存聚合）。
 */
@Data
public class SqlTemplateCluster {

    /** 数据源 */
    private String dsId;

    /** 归一化指纹 */
    private String sqlFingerprint;

    /** 归一化 SQL（聚类代表样本） */
    private String normalizedSql;

    /** 命中频次（同指纹聚合） */
    private Integer freq;

    /** 平均耗时 */
    private Long avgCostMs;

    /** 涉及表 */
    private List<String> involvedTables;

    /** 推断业务动作（biz_operation 聚合，取高频首样本） */
    private String bizAction;

    /** 首次出现时间 */
    private Date firstSeen;

    /** 最近出现时间 */
    private Date lastSeen;

    /** JOIN 表关系证据（RUNTIME_JOIN 写 module-004 kb_relation 用） */
    private List<JoinEvidence> joinEvidences;

    @Data
    public static class JoinEvidence {
        /** 表 A */
        private String tableA;
        /** 字段 A */
        private String columnA;
        /** 表 B */
        private String tableB;
        /** 字段 B */
        private String columnB;
        /** 关系类型 */
        private String relationType;
    }
}
