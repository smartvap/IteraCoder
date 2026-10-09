package com.agenthub.ai.rcagent.kbcrawler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * Schema 采集快照（kb_crawl_schema_snapshot）：每数据源每表一行，支撑变更检测 diff。
 */
@Data
@TableName(value = "kb_crawl_schema_snapshot")
public class KbCrawlSchemaSnapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源实例 ID */
    private String dsId;

    /** Schema/库名（对应 module-002 space_name） */
    private String schemaName;

    /** 表名 */
    private String tableName;

    /** 表类型：TABLE/VIEW */
    private String tableType;

    /** 列定义指纹（列名+类型排序哈希），变化即结构修改 */
    private String columnsHash;

    /** 字典视图 last_ddl_time/update_time（不可用为 NULL） */
    private Date ddlTime;

    /** 上轮采样行数估计（脱敏聚合值） */
    private Long rowCountEstimate;

    /** 上轮表元数据缓存 JSON（列清单/注释/统计摘要，脱敏） */
    private String tableMetaJson;

    /** 本轮快照采集时间 */
    private Date crawledAt;

    private Date createTime;

    private Date updateTime;
}
