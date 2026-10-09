-- ============================================================
-- 索引字典 kb_index 建表脚本（MySQL 8 / 库 ai_server）
-- 文件名：agent-hub-server/src/main/resources/sql/kb_index_init.sql
-- 来源：rca1.3.0.0 / schema-metadata-enhancement / data-model.md
-- 特点：CREATE TABLE IF NOT EXISTS 幂等，可重复执行；
--       不改动存量 kb_storage_init.sql / rcagent_init.sql / init.sql。
-- ============================================================

-- 1. kb_index 索引字典（Schema 元数据：索引名/有序列/唯一性/主键性/索引类型）
CREATE TABLE IF NOT EXISTS `kb_index` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `table_id` BIGINT NOT NULL COMMENT '所属表ID(kb_table.id,逻辑外键)',
  `db_type` VARCHAR(16) NOT NULL DEFAULT 'MYSQL' COMMENT '库类型:MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE',
  `schema_name` VARCHAR(128) DEFAULT NULL COMMENT 'Schema/库名',
  `table_name` VARCHAR(128) NOT NULL COMMENT '表名',
  `index_name` VARCHAR(128) NOT NULL COMMENT '索引名(MySQL主键索引为PRIMARY)',
  `index_columns` VARCHAR(1024) NOT NULL COMMENT '索引列清单JSON数组(按索引内列顺序)',
  `is_unique` TINYINT NOT NULL DEFAULT 0 COMMENT '是否唯一索引0/1',
  `is_primary` TINYINT NOT NULL DEFAULT 0 COMMENT '是否主键索引0/1',
  `index_type` VARCHAR(32) DEFAULT NULL COMMENT '索引类型(BTREE/FULLTEXT/HASH/NORMAL/BITMAP等,原样透传)',
  `knowledge_source` VARCHAR(16) NOT NULL DEFAULT 'SCHEMA' COMMENT '来源:SCHEMA/CODE/RUNTIME/LLM/HUMAN',
  `confidence` DECIMAL(4,3) NOT NULL DEFAULT 0.600 COMMENT '置信度',
  `verified` TINYINT NOT NULL DEFAULT 0 COMMENT '已验证0/1',
  `evidence` TEXT COMMENT '证据',
  `status` VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '状态:draft/active/needs_review/deprecated',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '正面命中',
  `error_count` INT NOT NULL DEFAULT 0 COMMENT '负面纠错',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_table_index` (`ds_id`,`table_id`,`index_name`),
  KEY `idx_ds_schema_table_name` (`ds_id`,`schema_name`,`table_name`),
  KEY `idx_status` (`status`),
  KEY `idx_verified_conf` (`verified`,`confidence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库A-索引字典';

-- 2. 自检（幂等脚本执行后应输出 1 行：表存在=1、唯一键列命中=3、索引名去重数=5）
SELECT
  (SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_index') AS kb_index_exists,
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_index'
      AND INDEX_NAME = 'uk_ds_table_index' AND NON_UNIQUE = 0) AS uk_expected_1,
  (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_index') AS index_count_expected_5;
