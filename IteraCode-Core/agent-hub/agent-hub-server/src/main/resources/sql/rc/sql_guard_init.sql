-- ============================================================
-- SQL 安全与验证防护 sql_guard_* 建表脚本（MySQL 8 / ai_server）
-- 独立文件：不改动存量 init.sql；IF NOT EXISTS 幂等可重复执行
-- 来源：data-model.md DDL
-- ============================================================

-- 1. sql_guard_policy 安全策略配置
CREATE TABLE IF NOT EXISTS `sql_guard_policy` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `policy_scope` VARCHAR(16) NOT NULL DEFAULT 'GLOBAL' COMMENT '策略作用域 GLOBAL/DS',
  `ds_id` VARCHAR(64) DEFAULT NULL COMMENT '数据源实例ID（DS 行必填）',
  `policy_name` VARCHAR(64) DEFAULT NULL COMMENT '策略名/备注',
  `max_rows_default` INT NOT NULL DEFAULT 1000 COMMENT '最大返回行数默认',
  `max_seconds_default` INT NOT NULL DEFAULT 10 COMMENT '最大执行时间默认(秒)',
  `select_star_policy` VARCHAR(16) NOT NULL DEFAULT 'BLOCK' COMMENT 'SELECT * 策略 BLOCK/ALLOW',
  `block_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT 'BLOCK 判定开关',
  `sensitive_policy` VARCHAR(24) NOT NULL DEFAULT 'DENY' COMMENT '敏感策略 MASK/DENY/ALLOW_BY_PERMISSION',
  `sensitive_source` VARCHAR(16) NOT NULL DEFAULT 'REGISTRY' COMMENT '敏感清单来源 REGISTRY/COMPLIANCE',
  `low_auto_enabled` TINYINT NOT NULL DEFAULT 1 COMMENT 'LOW 自动放行开关',
  `mvp_review_all` TINYINT NOT NULL DEFAULT 0 COMMENT 'MVP 全量人工审核开关',
  `high_review_required` TINYINT NOT NULL DEFAULT 1 COMMENT 'HIGH 强制人工审核',
  `repair_max_rounds` INT NOT NULL DEFAULT 3 COMMENT '回退重新生成最大轮数',
  `dialect_whitelist` TEXT COMMENT '允许方言JSON数组',
  `config_version` INT NOT NULL DEFAULT 1 COMMENT '配置版本号',
  `remark` VARCHAR(512) DEFAULT NULL COMMENT '备注',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0/1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scope_ds` (`policy_scope`,`ds_id`),
  KEY `idx_ds` (`ds_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL防护-安全策略配置';

-- 2. sql_guard_audit_log 验证审计流水
CREATE TABLE IF NOT EXISTS `sql_guard_audit_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `thread_id` VARCHAR(64) DEFAULT NULL COMMENT '工作流会话thread_id',
  `sql_fingerprint` VARCHAR(64) NOT NULL COMMENT '归一化SQL指纹SHA-256',
  `sql_abstract` VARCHAR(4000) NOT NULL COMMENT 'SQL抽象摘要(占位符化 无字面量)',
  `statement_type` VARCHAR(16) NOT NULL COMMENT '语句类型分类',
  `db_type` VARCHAR(16) NOT NULL DEFAULT 'MYSQL' COMMENT '库类型',
  `involved_tables` TEXT COMMENT '涉及表名JSON数组',
  `risk_level` VARCHAR(16) NOT NULL COMMENT '风险等级',
  `verdict` VARCHAR(16) NOT NULL COMMENT '处置 ALLOW/REVIEW/BLOCK',
  `block_reason` VARCHAR(1024) DEFAULT NULL COMMENT 'BLOCK/异常原因摘要',
  `level_l1_result` VARCHAR(16) NOT NULL DEFAULT 'SKIP' COMMENT 'L1 Syntax 结果',
  `level_l2_result` VARCHAR(16) NOT NULL DEFAULT 'SKIP' COMMENT 'L2 Schema 结果',
  `level_l3_result` VARCHAR(16) NOT NULL DEFAULT 'SKIP' COMMENT 'L3 Semantic 结果',
  `level_l4_result` VARCHAR(16) NOT NULL DEFAULT 'SKIP' COMMENT 'L4 Execution 结果',
  `level_l5_result` VARCHAR(16) NOT NULL DEFAULT 'SKIP' COMMENT 'L5 Result 结果',
  `levels_summary` TEXT COMMENT '各级结果摘要JSON(截断)',
  `fix_suggestion` TEXT COMMENT '修复建议摘要JSON',
  `cost_ms` BIGINT DEFAULT NULL COMMENT '验证耗时ms',
  `result_row_count` INT DEFAULT NULL COMMENT '执行实际行数',
  `truncated` TINYINT DEFAULT NULL COMMENT '是否截断',
  `guard_status` VARCHAR(16) NOT NULL DEFAULT 'VALIDATED' COMMENT '审计状态 VALIDATED/EXECUTED/REJECTED/TIMEOUT',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除 0/1',
  PRIMARY KEY (`id`),
  KEY `idx_ds_time` (`ds_id`,`create_time`),
  KEY `idx_thread` (`thread_id`),
  KEY `idx_fingerprint` (`sql_fingerprint`),
  KEY `idx_risk` (`risk_level`,`create_time`),
  KEY `idx_verdict` (`verdict`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SQL防护-验证审计流水';

-- 3. 初始化 GLOBAL 默认策略 seed（幂等：已存在不重复插入）
INSERT INTO `sql_guard_policy`
  (`policy_scope`, `ds_id`, `policy_name`, `max_rows_default`, `max_seconds_default`,
   `select_star_policy`, `block_enabled`, `sensitive_policy`, `sensitive_source`,
   `low_auto_enabled`, `mvp_review_all`, `high_review_required`, `repair_max_rounds`,
   `dialect_whitelist`, `config_version`, `remark`)
SELECT 'GLOBAL', NULL, 'GLOBAL-DEFAULT', 1000, 10,
       'BLOCK', 1, 'DENY', 'REGISTRY',
       1, 0, 1, 3,
       '["MYSQL","ORACLE","OCEANBASE_MYSQL","OCEANBASE_ORACLE"]', 1,
       'sql-validate-guard 全局默认安全策略'
WHERE NOT EXISTS (
    SELECT 1 FROM `sql_guard_policy`
    WHERE `policy_scope` = 'GLOBAL' AND `ds_id` IS NULL AND `is_deleted` = 0
);
