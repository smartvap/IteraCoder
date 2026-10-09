-- ============================================================
-- 知识库A结构化存储与审核中心 kb_* 建表脚本（MySQL 8 / ai_server）
-- 独立文件：不改动存量 init.sql；IF NOT EXISTS 幂等可重复执行
-- 来源：data-model.md DDL
-- ============================================================

-- 1. kb_table 表字典
CREATE TABLE IF NOT EXISTS `kb_table` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `db_type` VARCHAR(16) NOT NULL DEFAULT 'MYSQL' COMMENT '库类型:MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE',
  `schema_name` VARCHAR(128) DEFAULT NULL COMMENT 'Schema/库名',
  `table_name` VARCHAR(128) NOT NULL COMMENT '表名',
  `table_type` VARCHAR(16) DEFAULT 'TABLE' COMMENT '表类型:TABLE/VIEW',
  `comment_text` VARCHAR(1024) DEFAULT NULL COMMENT '表注释',
  `semantic_tags` TEXT COMMENT '语义标签JSON数组',
  `sample_stats` TEXT COMMENT '采样统计JSON(脱敏)',
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
  UNIQUE KEY `uk_ds_schema_table` (`ds_id`,`schema_name`,`table_name`),
  KEY `idx_status` (`status`),
  KEY `idx_verified_conf` (`verified`,`confidence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库A-表字典';

-- 2. kb_column 字段字典
CREATE TABLE IF NOT EXISTS `kb_column` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `table_id` BIGINT NOT NULL COMMENT '所属表ID(kb_table.id)',
  `db_type` VARCHAR(16) NOT NULL DEFAULT 'MYSQL' COMMENT '库类型',
  `schema_name` VARCHAR(128) DEFAULT NULL COMMENT 'Schema名',
  `table_name` VARCHAR(128) NOT NULL COMMENT '表名',
  `column_name` VARCHAR(128) NOT NULL COMMENT '字段名',
  `data_type` VARCHAR(64) NOT NULL COMMENT '数据类型',
  `is_pk` TINYINT NOT NULL DEFAULT 0 COMMENT '主键标记',
  `is_fk` TINYINT NOT NULL DEFAULT 0 COMMENT '外键标记',
  `comment_text` VARCHAR(1024) DEFAULT NULL COMMENT '字段注释',
  `semantic_tags` TEXT COMMENT '语义标签JSON数组',
  `sample_stats` TEXT COMMENT '采样统计JSON(脱敏)',
  `knowledge_source` VARCHAR(16) NOT NULL DEFAULT 'SCHEMA' COMMENT '来源',
  `confidence` DECIMAL(4,3) NOT NULL DEFAULT 0.600 COMMENT '置信度',
  `verified` TINYINT NOT NULL DEFAULT 0 COMMENT '已验证0/1',
  `evidence` TEXT COMMENT '证据',
  `status` VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '状态',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '正面命中',
  `error_count` INT NOT NULL DEFAULT 0 COMMENT '负面纠错',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_table_column` (`ds_id`,`table_id`,`column_name`),
  KEY `idx_ds_schema_table_name` (`ds_id`,`schema_name`,`table_name`),
  KEY `idx_status` (`status`),
  KEY `idx_verified_conf` (`verified`,`confidence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库A-字段字典';

-- 3. kb_business_concept 业务概念
CREATE TABLE IF NOT EXISTS `kb_business_concept` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `concept_name` VARCHAR(128) NOT NULL COMMENT '概念名',
  `definition` TEXT NOT NULL COMMENT '概念定义',
  `synonyms` TEXT COMMENT '同义词JSON数组',
  `related_tables` TEXT COMMENT '关联表JSON数组',
  `related_columns` TEXT COMMENT '关联字段JSON数组',
  `related_sql_ids` TEXT COMMENT '关联SQL模板id JSON数组',
  `space_name` VARCHAR(128) DEFAULT NULL COMMENT '知识库B知识空间名',
  `knowledge_source` VARCHAR(16) NOT NULL DEFAULT 'LLM' COMMENT '来源',
  `confidence` DECIMAL(4,3) NOT NULL DEFAULT 0.600 COMMENT '置信度',
  `verified` TINYINT NOT NULL DEFAULT 0 COMMENT '已验证0/1',
  `evidence` TEXT COMMENT '证据',
  `status` VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '状态',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '正面命中',
  `error_count` INT NOT NULL DEFAULT 0 COMMENT '负面纠错',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_concept` (`ds_id`,`concept_name`),
  KEY `idx_status` (`status`),
  KEY `idx_verified_conf` (`verified`,`confidence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库A-业务概念';

-- 4. kb_metric 指标定义
CREATE TABLE IF NOT EXISTS `kb_metric` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `metric_name` VARCHAR(128) NOT NULL COMMENT '指标名',
  `definition` TEXT NOT NULL COMMENT '指标定义',
  `calculation_rule` TEXT COMMENT '计算规则/口径',
  `related_tables` TEXT COMMENT '关联表JSON数组',
  `related_columns` TEXT COMMENT '关联字段JSON数组',
  `space_name` VARCHAR(128) DEFAULT NULL COMMENT '知识库B知识空间名',
  `knowledge_source` VARCHAR(16) NOT NULL DEFAULT 'LLM' COMMENT '来源',
  `confidence` DECIMAL(4,3) NOT NULL DEFAULT 0.600 COMMENT '置信度',
  `verified` TINYINT NOT NULL DEFAULT 0 COMMENT '已验证0/1',
  `evidence` TEXT COMMENT '证据',
  `status` VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '状态',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '正面命中',
  `error_count` INT NOT NULL DEFAULT 0 COMMENT '负面纠错',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_metric` (`ds_id`,`metric_name`),
  KEY `idx_status` (`status`),
  KEY `idx_verified_conf` (`verified`,`confidence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库A-指标定义';

-- 5. kb_sql_template SQL模板
CREATE TABLE IF NOT EXISTS `kb_sql_template` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `db_type` VARCHAR(16) NOT NULL DEFAULT 'MYSQL' COMMENT '库类型',
  `biz_action` VARCHAR(128) DEFAULT NULL COMMENT '业务动作',
  `normalized_sql` TEXT NOT NULL COMMENT '归一化SQL',
  `sql_fingerprint` VARCHAR(64) NOT NULL COMMENT '归一化SQL指纹',
  `involved_tables` TEXT COMMENT '涉及表JSON数组',
  `sample_questions` TEXT COMMENT '样本问法JSON数组',
  `freq` INT NOT NULL DEFAULT 0 COMMENT '命中频次',
  `accuracy` DECIMAL(4,3) DEFAULT NULL COMMENT '准确率',
  `knowledge_source` VARCHAR(16) NOT NULL DEFAULT 'RUNTIME' COMMENT '来源',
  `confidence` DECIMAL(4,3) NOT NULL DEFAULT 0.600 COMMENT '置信度',
  `verified` TINYINT NOT NULL DEFAULT 0 COMMENT '已验证0/1',
  `evidence` TEXT COMMENT '证据',
  `status` VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '状态',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '正面命中',
  `error_count` INT NOT NULL DEFAULT 0 COMMENT '负面纠错',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_fingerprint` (`ds_id`,`sql_fingerprint`),
  KEY `idx_ds_biz_action` (`ds_id`,`biz_action`),
  KEY `idx_status` (`status`),
  KEY `idx_verified_conf` (`verified`,`confidence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库A-SQL模板';

-- 6. kb_biz_link 业务链路
CREATE TABLE IF NOT EXISTS `kb_biz_link` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `biz_operation` VARCHAR(128) NOT NULL COMMENT '业务操作名',
  `service_class` VARCHAR(256) DEFAULT NULL COMMENT '入口service类',
  `dao_class` VARCHAR(256) DEFAULT NULL COMMENT 'dao类',
  `sql_template_id` BIGINT DEFAULT NULL COMMENT '关联SQL模板ID',
  `related_tables` TEXT COMMENT '涉及表JSON数组',
  `knowledge_source` VARCHAR(16) NOT NULL DEFAULT 'CODE' COMMENT '来源',
  `confidence` DECIMAL(4,3) NOT NULL DEFAULT 0.600 COMMENT '置信度',
  `verified` TINYINT NOT NULL DEFAULT 0 COMMENT '已验证0/1',
  `evidence` TEXT COMMENT '证据',
  `status` VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '状态',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '正面命中',
  `error_count` INT NOT NULL DEFAULT 0 COMMENT '负面纠错',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_operation_sql` (`ds_id`,`biz_operation`,`sql_template_id`),
  KEY `idx_ds_service` (`ds_id`,`service_class`),
  KEY `idx_sql_template_id` (`sql_template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库A-业务链路';

-- 7. kb_relation 表关系
CREATE TABLE IF NOT EXISTS `kb_relation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `db_type` VARCHAR(16) NOT NULL DEFAULT 'MYSQL' COMMENT '库类型',
  `table_a` VARCHAR(128) NOT NULL COMMENT '关系表A',
  `column_a` VARCHAR(128) DEFAULT NULL COMMENT '关系字段A',
  `table_b` VARCHAR(128) NOT NULL COMMENT '关系表B',
  `column_b` VARCHAR(128) DEFAULT NULL COMMENT '关系字段B',
  `join_fields` TEXT COMMENT 'join字段映射JSON数组',
  `relation_type` VARCHAR(16) NOT NULL DEFAULT 'REFERENCE' COMMENT 'FK/REFERENCE',
  `relation_source` VARCHAR(16) NOT NULL DEFAULT 'RUNTIME_JOIN' COMMENT 'FK/CODE/RUNTIME_JOIN',
  `knowledge_source` VARCHAR(16) NOT NULL DEFAULT 'RUNTIME' COMMENT '来源',
  `confidence` DECIMAL(4,3) NOT NULL DEFAULT 0.600 COMMENT '置信度',
  `verified` TINYINT NOT NULL DEFAULT 0 COMMENT '已验证0/1',
  `evidence` TEXT COMMENT '证据',
  `status` VARCHAR(16) NOT NULL DEFAULT 'draft' COMMENT '状态',
  `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
  `hit_count` INT NOT NULL DEFAULT 0 COMMENT '正面命中',
  `error_count` INT NOT NULL DEFAULT 0 COMMENT '负面纠错',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_relation` (`ds_id`,`table_a`,`table_b`,`relation_type`),
  KEY `idx_table_a` (`ds_id`,`table_a`),
  KEY `idx_table_b` (`ds_id`,`table_b`),
  KEY `idx_verified_conf` (`verified`,`confidence`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库A-表关系';

-- 8. kb_feedback 反馈记录
CREATE TABLE IF NOT EXISTS `kb_feedback` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `session_id` VARCHAR(64) DEFAULT NULL COMMENT '关联会话/工作流thread_id',
  `question` TEXT NOT NULL COMMENT '原始问题',
  `generated_sql` TEXT COMMENT '生成的SQL',
  `modified_sql` TEXT COMMENT '用户修正SQL',
  `change_type` VARCHAR(32) DEFAULT NULL COMMENT '修改类型',
  `feedback_type` VARCHAR(16) DEFAULT 'POSITIVE' COMMENT '反馈类型',
  `user_comment` TEXT COMMENT '用户文本反馈',
  `review_conclusion` VARCHAR(32) DEFAULT NULL COMMENT '审核结论:PENDING/APPROVED/REJECTED',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '处理状态',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ds_status` (`ds_id`,`status`),
  KEY `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库A-反馈记录';

-- 9. kb_review_task 审核任务
CREATE TABLE IF NOT EXISTS `kb_review_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `task_type` VARCHAR(32) NOT NULL COMMENT '任务类型',
  `target_object_type` VARCHAR(32) NOT NULL COMMENT '目标对象类型',
  `target_object` VARCHAR(256) NOT NULL COMMENT '目标对象标识JSON',
  `suggested_change` TEXT COMMENT '结构化变更建议JSON',
  `evidence` TEXT COMMENT '证据',
  `status` VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '任务状态:pending/approved/rejected',
  `reviewer` VARCHAR(64) DEFAULT NULL COMMENT '审核人',
  `review_comment` VARCHAR(1024) DEFAULT NULL COMMENT '审核意见',
  `reviewed_at` DATETIME DEFAULT NULL COMMENT '审核时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ds_status` (`ds_id`,`status`),
  KEY `idx_task_type` (`ds_id`,`task_type`),
  KEY `idx_target` (`target_object_type`,`status`),
  KEY `idx_reviewer_time` (`reviewer`,`reviewed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库A-审核任务';
