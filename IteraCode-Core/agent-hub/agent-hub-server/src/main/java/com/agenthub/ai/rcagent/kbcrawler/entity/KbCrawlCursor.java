package com.agenthub.ai.rcagent.kbcrawler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 采集游标（kb_crawl_cursor）：增量采集与并发安全游标，key-value 结构。
 */
@Data
@TableName(value = "kb_crawl_cursor")
public class KbCrawlCursor {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源实例 ID */
    private String dsId;

    /** 游标键：SCHEMA_LAST_DDL/SCHEMA_FULL_HASH/MAPPER_SRC_FINGERPRINT/RUNTIME_BATCH_ID */
    private String cursorKey;

    /** 库/Schema 名；非空间级游标为空串（NOT NULL DEFAULT ''，避免唯一键对 NULL 不去重） */
    private String spaceName;

    /** 游标值：标量或 JSON */
    private String cursorValue;

    /** 扩展信息 JSON */
    private String extraInfo;

    private Date updateTime;
}
