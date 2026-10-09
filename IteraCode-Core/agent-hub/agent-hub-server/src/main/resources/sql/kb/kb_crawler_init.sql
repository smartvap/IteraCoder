-- ============================================================
-- kb-schema-crawler 采集运营/快照建表脚本（MySQL 8 / ai_server）
-- 仅本模块三张 kb_crawl_* 表；知识目标表 kb_* 由 module-004 kb_storage_init.sql 负责
-- IF NOT EXISTS 幂等可重复执行；不改动存量 init.sql
-- 来源：kb-schema-crawler data-model.md DDL
-- ============================================================

-- 1. kb_crawl_task 采集任务状态
CREATE TABLE IF NOT EXISTS `kb_crawl_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `task_type` VARCHAR(32) NOT NULL COMMENT '采集类型:SCHEMA_FULL/SCHEMA_INCR/MAPPER_PARSE/RUNTIME_COLLECT/CHANGE_DETECT',
  `task_name` VARCHAR(128) DEFAULT NULL COMMENT '任务显示名',
  `status` VARCHAR(16) NOT NULL DEFAULT 'scheduled' COMMENT '任务状态:scheduled/running/success/failed',
  `pause_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '暂停标记:1=暂停(调度跳过)',
  `trigger_type` VARCHAR(16) NOT NULL DEFAULT 'manual' COMMENT '触发来源:MANUAL/SCHEDULE/RELEASE',
  `last_run_time` DATETIME DEFAULT NULL COMMENT '最近运行时间',
  `last_result` VARCHAR(16) DEFAULT NULL COMMENT '最近结果:success/failed/running',
  `last_error` VARCHAR(1000) DEFAULT NULL COMMENT '最近失败摘要(截断,禁敏感)',
  `run_count` INT NOT NULL DEFAULT 0 COMMENT '累计运行次数',
  `remark` VARCHAR(512) DEFAULT NULL COMMENT '备注/降级说明',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_task_type` (`ds_id`,`task_type`),
  KEY `idx_status` (`status`),
  KEY `idx_pause_flag` (`pause_flag`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RCAgent-采集任务状态';

-- 2. kb_crawl_cursor 采集游标
CREATE TABLE IF NOT EXISTS `kb_crawl_cursor` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `cursor_key` VARCHAR(64) NOT NULL COMMENT '游标键:SCHEMA_LAST_DDL/SCHEMA_FULL_HASH/MAPPER_SRC_FINGERPRINT/RUNTIME_BATCH_ID',
  `space_name` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '库/Schema名;非空间级游标为空串',
  `cursor_value` TEXT NOT NULL COMMENT '游标值(标量或JSON)',
  `extra_info` TEXT COMMENT '扩展信息JSON',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '游标更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_space_key` (`ds_id`,`cursor_key`,`space_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RCAgent-采集游标';

-- 3. kb_crawl_schema_snapshot Schema快照
CREATE TABLE IF NOT EXISTS `kb_crawl_schema_snapshot` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `schema_name` VARCHAR(128) DEFAULT NULL COMMENT 'Schema/库名',
  `table_name` VARCHAR(128) NOT NULL COMMENT '表名',
  `table_type` VARCHAR(16) NOT NULL DEFAULT 'TABLE' COMMENT '表类型:TABLE/VIEW',
  `columns_hash` VARCHAR(64) NOT NULL COMMENT '列定义指纹(列名+类型排序哈希)',
  `ddl_time` DATETIME DEFAULT NULL COMMENT '字典视图last_ddl_time/update_time(不可用为NULL)',
  `row_count_estimate` BIGINT DEFAULT NULL COMMENT '上轮采样行数估计(脱敏聚合值)',
  `table_meta_json` TEXT COMMENT '上轮表元数据缓存JSON(注释/统计摘要,脱敏)',
  `crawled_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '本轮快照采集时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_schema_table` (`ds_id`,`schema_name`,`table_name`),
  KEY `idx_ds_ddl_time` (`ds_id`,`ddl_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='RCAgent-Schema采集快照';
