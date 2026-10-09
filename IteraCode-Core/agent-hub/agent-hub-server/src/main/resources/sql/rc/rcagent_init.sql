-- ============================================================
-- RCAgent 大需求 9 模块全量建表初始化脚本（rcagent_init.sql）
-- 目标库: ai_server (MySQL 8, utf8mb4)  |  创建: 2026-09-07
-- 来源: .aspirecode/sdd/rca1.0.0.0/<module>/data-model.md 的 DDL 原样提取
-- 特点: CREATE TABLE IF NOT EXISTS 幂等，可重复执行；不含 DROP/TRUNCATE，
--       不触碰存量表（workflow_metadata/GRAPH_*/QRTZ_*/tb_user 等）。
-- 说明: 本文件为独立新增文件，供后续环境重建使用，不影响存量 init.sql。
-- ============================================================


-- ============================================================
-- [db-access-common] t_ds_config
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_ds_config` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '全局唯一数据源标识',
  `ds_name` VARCHAR(128) NOT NULL COMMENT '数据源显示名',
  `system_code` VARCHAR(64) DEFAULT NULL COMMENT '归属系统编码(CRM/OA/...)',
  `db_type` VARCHAR(32) NOT NULL COMMENT '库类型(MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE)',
  `jdbc_url` VARCHAR(512) NOT NULL COMMENT 'JDBC连接串(禁止内嵌账密)',
  `driver_class` VARCHAR(256) DEFAULT NULL COMMENT '驱动类名(可空,按db_type推断)',
  `readonly_user` VARCHAR(128) NOT NULL COMMENT '只读账号用户名',
  `readonly_password_ref` VARCHAR(256) NOT NULL COMMENT '只读密码环境变量引用,如 ${DBACCESS_DS_CRM_ORACLE_PROD_PWD}',
  `owner_group` VARCHAR(128) DEFAULT NULL COMMENT '属主组/责任团队',
  `space_name` VARCHAR(128) DEFAULT NULL COMMENT 'Schema/Space名',
  `space_names` JSON DEFAULT NULL COMMENT '可访问库集合JSON,如["CRM_A","CRM_B"];空/未配置时回落space_name单库',
  `whitelist_json` JSON DEFAULT NULL COMMENT '白名单(tables/columns/mode)',
  `limits_json` JSON DEFAULT NULL COMMENT '资源限制覆盖(maxRows/maxSeconds/sensitivePolicy/allowSelectStar)',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态:1=启用 0=停用',
  `remark` VARCHAR(512) DEFAULT NULL COMMENT '备注',
  `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=正常 1=删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_id` (`ds_id`),
  KEY `idx_system_code` (`system_code`),
  KEY `idx_db_type` (`db_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RCAgent数据源注册表';

-- ============================================================
-- [kb-storage-review] kb_table
-- ============================================================
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

-- ============================================================
-- [kb-storage-review] kb_column
-- ============================================================
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

-- ============================================================
-- [kb-storage-review] kb_business_concept
-- ============================================================
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

-- ============================================================
-- [kb-storage-review] kb_metric
-- ============================================================
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

-- ============================================================
-- [kb-storage-review] kb_sql_template
-- ============================================================
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

-- ============================================================
-- [kb-storage-review] kb_biz_link
-- ============================================================
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

-- ============================================================
-- [kb-storage-review] kb_relation
-- ============================================================
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

-- ============================================================
-- [kb-storage-review] kb_feedback
-- ============================================================
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

-- ============================================================
-- [kb-storage-review] kb_review_task
-- ============================================================
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

-- ============================================================
-- [kb-schema-crawler] kb_crawl_task
-- ============================================================
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

-- ============================================================
-- [kb-schema-crawler] kb_crawl_cursor
-- ============================================================
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

-- ============================================================
-- [kb-schema-crawler] kb_crawl_schema_snapshot
-- ============================================================
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

-- ============================================================
-- [dual-retrieval] rcagent_retrieval_log
-- ============================================================
CREATE TABLE IF NOT EXISTS `rcagent_retrieval_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `thread_id` VARCHAR(64) DEFAULT NULL COMMENT '关联工作流会话thread_id',
  `question` TEXT NOT NULL COMMENT '原始问题(日志截断输出)',
  `query_elements_json` TEXT COMMENT '检索要素JSON',
  `matched_elements_json` TEXT COMMENT '命中要素JSON数组',
  `missing_elements_json` TEXT COMMENT '未命中要素JSON数组',
  `channel_hit_json` TEXT COMMENT '各路命中统计JSON',
  `context_item_count` INT NOT NULL DEFAULT 0 COMMENT '输出上下文条目数',
  `context_token_estimate` INT DEFAULT NULL COMMENT '上下文token估算',
  `cold_start` TINYINT NOT NULL DEFAULT 0 COMMENT '冷启动信号0/1',
  `allow_cold_start` TINYINT NOT NULL DEFAULT 0 COMMENT '允许冷生成降级0/1',
  `space_used` VARCHAR(128) DEFAULT NULL COMMENT '命中的知识库B空间名',
  `kb_b_unavailable` TINYINT NOT NULL DEFAULT 0 COMMENT '知识库B不可用被降级0/1',
  `rerank_enabled` TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用Rerank0/1',
  `latency_ms` INT NOT NULL DEFAULT 0 COMMENT '单次检索耗时ms',
  `matched_template_id` BIGINT DEFAULT NULL COMMENT 'P2命中正确SQL模板id',
  `matched_flag` TINYINT NOT NULL DEFAULT 0 COMMENT 'P2是否命中正确SQL0/1',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_ds_time` (`ds_id`,`create_time`),
  KEY `idx_thread_id` (`thread_id`),
  KEY `idx_cold_start` (`ds_id`,`cold_start`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='双重知识库融合检索-质量观测日志';

-- ============================================================
-- [sql-validate-guard] sql_guard_policy
-- ============================================================
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

-- ============================================================
-- [sql-validate-guard] sql_guard_audit_log
-- ============================================================
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

-- ============================================================
-- [feedback-loop] fb_feedback_flow
-- ============================================================
CREATE TABLE IF NOT EXISTS `fb_feedback_flow` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `feedback_id` BIGINT NOT NULL COMMENT '关联kb_feedback.id(module-004)',
  `session_id` VARCHAR(64) DEFAULT NULL COMMENT '关联会话/工作流thread_id',
  `feedback_type` VARCHAR(16) NOT NULL COMMENT '反馈类型:POSITIVE/NEGATIVE/CORRECTION/EXPLANATION',
  `change_type` VARCHAR(32) DEFAULT NULL COMMENT '修改类型:SQL_ERROR/FIELD_ERROR/SEMANTIC_ERROR/JOIN_ERROR/TIME_RANGE_ERROR/AGG_ERROR/ATTRIBUTION_*/CONCEPT_SUPPLEMENT/OTHER',
  `change_confidence` DECIMAL(4,3) DEFAULT NULL COMMENT '修改类型判定置信度',
  `diff_summary` TEXT COMMENT 'AST diff摘要JSON(不含全量SQL)',
  `suggestions` TEXT COMMENT '修正建议快照JSON数组',
  `review_task_ids` TEXT COMMENT '关联审核任务id JSON数组',
  `analyze_status` VARCHAR(16) NOT NULL DEFAULT 'RECEIVED' COMMENT '分析阶段:RECEIVED/DIFF_ANALYZING/SUGGESTING/MANUAL_REQUIRED/REVIEW_CREATED/ANALYZE_FAILED',
  `status` VARCHAR(24) NOT NULL DEFAULT 'RECEIVED' COMMENT '闭环状态:RECEIVED/ANALYZING/REVIEWING/APPROVED/REJECTED/WRITTEN_BACK/CLOSED/FAILED',
  `writeback_repo` VARCHAR(8) DEFAULT NULL COMMENT '已完成回写目标 A/B/AB',
  `wiki_writeback_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '是否含B库回写需求',
  `confidence_pushed` TINYINT NOT NULL DEFAULT 0 COMMENT '置信度信号是否已推送',
  `signal_keys` TEXT COMMENT '已用置信度幂等键JSON数组',
  `a_writeback_done` TINYINT NOT NULL DEFAULT 0 COMMENT '库A回写是否完成',
  `b_writeback_done` TINYINT NOT NULL DEFAULT 0 COMMENT '库B回写是否完成',
  `error_msg` VARCHAR(1024) DEFAULT NULL COMMENT '最近错误信息(截断)',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '失败重试次数',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_feedback` (`ds_id`,`feedback_id`),
  KEY `idx_ds_status` (`ds_id`,`status`),
  KEY `idx_analyze_status` (`analyze_status`),
  KEY `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='反馈闭环处理流';

-- ============================================================
-- [feedback-loop] fb_wiki_writeback
-- ============================================================
CREATE TABLE IF NOT EXISTS `fb_wiki_writeback` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `feedback_id` BIGINT NOT NULL COMMENT '关联反馈id(kb_feedback.id)',
  `review_task_id` BIGINT NOT NULL COMMENT '关联审核任务id(kb_review_task.id)',
  `suggestion_no` INT NOT NULL DEFAULT 0 COMMENT 'suggestions快照下标',
  `wiki_space` VARCHAR(128) NOT NULL COMMENT 'llm-wiki空间名',
  `page_path` VARCHAR(512) NOT NULL COMMENT '页面路径(可含子目录)',
  `page_title` VARCHAR(256) DEFAULT NULL COMMENT '页面标题',
  `page_content` MEDIUMTEXT COMMENT '页面正文Markdown',
  `page_type` VARCHAR(16) NOT NULL DEFAULT 'FAQ' COMMENT '页面类型:FAQ/CONCEPT/METRIC_CALIBER',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '出站状态:PENDING/RUNNING/SUCCESS/FAILED/CANCELED',
  `remote_task_id` VARCHAR(128) DEFAULT NULL COMMENT 'llm-wiki异步sync任务id',
  `retry_count` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  `last_error` VARCHAR(1024) DEFAULT NULL COMMENT '最近错误(截断)',
  `next_retry_time` DATETIME DEFAULT NULL COMMENT '下次重试时间',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_page` (`ds_id`,`review_task_id`,`page_path`),
  KEY `idx_ds_status_retry` (`ds_id`,`status`,`next_retry_time`),
  KEY `idx_feedback_id` (`feedback_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='B库llm-wiki回写出站任务';

-- ============================================================
-- [rca-attribution-workflow] rcagent_attribution_task
-- ============================================================
CREATE TABLE IF NOT EXISTS `rcagent_attribution_task` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `task_id` VARCHAR(64) NOT NULL COMMENT '归因任务业务ID',
  `thread_id` VARCHAR(64) DEFAULT NULL COMMENT '关联工作流会话thread_id',
  `parent_task_id` VARCHAR(64) DEFAULT NULL COMMENT '追问链父任务task_id',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `metric_name` VARCHAR(128) NOT NULL COMMENT '指标名',
  `kb_metric_id` BIGINT DEFAULT NULL COMMENT '命中kb_metric.id',
  `metric_definition_verified` TINYINT NOT NULL DEFAULT 0 COMMENT '是否有verified/active口径定义',
  `metric_knowledge_json` TEXT COMMENT '指标口径快照JSON',
  `trigger_type` VARCHAR(32) NOT NULL COMMENT '触发方式: METRIC_ANOMALY/USER_FOLLOW_UP',
  `question` TEXT COMMENT '用户原始追问/主问题',
  `query_context_json` TEXT COMMENT '追问上下文JSON',
  `time_range_json` TEXT COMMENT '时间窗JSON',
  `compare_type` VARCHAR(16) NOT NULL DEFAULT 'MOM' COMMENT '对比方式: MOM/YOY/CUSTOM',
  `status` VARCHAR(32) NOT NULL COMMENT '任务状态: DETECTING/DRILLING/EXECUTING/CONCLUDING/FINISHED/FAILED/CANCELLED/EXPLAINING/QUERY_REVIEWING/CONCLUSION_REVIEWING',
  `review_state` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '审核阶段: NONE/QUERY_REVIEWING/CONCLUSION_REVIEWING',
  `anomaly_json` TEXT COMMENT '异常识别结果快照JSON',
  `drill_path_json` TEXT COMMENT '已下钻路径JSON',
  `drill_depth` INT NOT NULL DEFAULT 0 COMMENT '当前下钻深度',
  `query_count` INT NOT NULL DEFAULT 0 COMMENT '已生成归因查询次数',
  `quality_flags_json` TEXT COMMENT '口径/数据质量信号JSON',
  `conclusion_json` TEXT COMMENT '结构化结论JSON',
  `conclusion_summary` TEXT COMMENT '自然语言结论摘要',
  `confidence` DECIMAL(5,4) DEFAULT NULL COMMENT '归因结论置信度',
  `result_status` VARCHAR(32) DEFAULT NULL COMMENT '结果状态: STABLE/CONCLUSION/DATA_ISSUE/DEGRADED/REJECTED_RETRY',
  `sensitive_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '是否命中敏感归因',
  `error_msg` VARCHAR(500) DEFAULT NULL COMMENT '失败信息',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_id` (`task_id`),
  KEY `idx_thread_id` (`thread_id`),
  KEY `idx_ds_status_time` (`ds_id`, `status`, `create_time`),
  KEY `idx_parent_task_id` (`parent_task_id`),
  KEY `idx_metric` (`ds_id`, `metric_name`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归因任务表(rca-attribution-workflow)';

-- ============================================================
-- [rca-attribution-workflow] rcagent_attribution_node
-- ============================================================
CREATE TABLE IF NOT EXISTS `rcagent_attribution_node` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `node_id` VARCHAR(64) NOT NULL COMMENT '下钻节点业务ID',
  `task_id` VARCHAR(64) NOT NULL COMMENT '归属归因任务',
  `layer` INT NOT NULL DEFAULT 1 COMMENT '下钻层级',
  `dimension` VARCHAR(128) NOT NULL COMMENT '下钻维度',
  `dimension_label` VARCHAR(128) DEFAULT NULL COMMENT '维度展示名',
  `node_type` VARCHAR(32) NOT NULL DEFAULT 'GROUP_DRILL' COMMENT '节点类型: ROOT/GROUP_DRILL/CROSS_DRILL/QUALITY_CHECK',
  `parent_node_id` VARCHAR(64) DEFAULT NULL COMMENT '上一层节点',
  `dimension_value` VARCHAR(255) DEFAULT NULL COMMENT '聚焦子群值',
  `compare_summary_json` TEXT COMMENT '本层对比摘要JSON',
  `query_ids` TEXT COMMENT '证据查询id数组JSON',
  `factor_ids` TEXT COMMENT '候选因子id数组JSON',
  `drill_status` VARCHAR(32) NOT NULL DEFAULT 'EXECUTED' COMMENT '节点执行状态: EXECUTED/SKIPPED/REVIEWING/FAILED',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '展示顺序',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_node_id` (`node_id`),
  KEY `idx_task_layer` (`task_id`, `layer`),
  KEY `idx_task_dimension` (`task_id`, `dimension`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归因下钻节点表';

-- ============================================================
-- [rca-attribution-workflow] rcagent_attribution_factor
-- ============================================================
CREATE TABLE IF NOT EXISTS `rcagent_attribution_factor` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `factor_id` VARCHAR(64) NOT NULL COMMENT '因子业务ID',
  `task_id` VARCHAR(64) NOT NULL COMMENT '归属归因任务',
  `node_id` VARCHAR(64) NOT NULL COMMENT '归属下钻节点',
  `factor_type` VARCHAR(32) NOT NULL DEFAULT 'SEGMENT_CONTRIB' COMMENT '因子类型',
  `dimension` VARCHAR(128) NOT NULL COMMENT '维度名',
  `dimension_label` VARCHAR(128) DEFAULT NULL COMMENT '维度展示名',
  `segment_key` VARCHAR(255) NOT NULL COMMENT '子群键',
  `segment_value` VARCHAR(255) NOT NULL COMMENT '子群展示值',
  `cur_value` DECIMAL(24,4) DEFAULT NULL COMMENT '本期子群值',
  `base_value` DECIMAL(24,4) DEFAULT NULL COMMENT '基期子群值',
  `delta_value` DECIMAL(24,4) DEFAULT NULL COMMENT '子群变化量',
  `delta_ratio` DECIMAL(12,4) DEFAULT NULL COMMENT '子群变化率%',
  `contribution` DECIMAL(24,4) DEFAULT NULL COMMENT '对总变化贡献量',
  `contribution_ratio` DECIMAL(8,4) DEFAULT NULL COMMENT '贡献占比%',
  `cur_share` DECIMAL(8,4) DEFAULT NULL COMMENT '本期占比%',
  `base_share` DECIMAL(8,4) DEFAULT NULL COMMENT '基期占比%',
  `segment_change` VARCHAR(16) DEFAULT NULL COMMENT '变化类型: INCREASE/DECREASE/NEW/DISAPPEARED/STABLE',
  `is_main` TINYINT NOT NULL DEFAULT 0 COMMENT '是否主因',
  `rank` INT NOT NULL DEFAULT 0 COMMENT '贡献度排序位',
  `confidence` DECIMAL(5,4) DEFAULT NULL COMMENT '因子置信度',
  `hypothesis` TEXT COMMENT '假设解释(非因果断言)',
  `evidence_query_ids` TEXT COMMENT '支撑证据查询id数组JSON',
  `sensitive_flag` TINYINT NOT NULL DEFAULT 0 COMMENT '是否敏感因子',
  `status` VARCHAR(32) NOT NULL DEFAULT 'CANDIDATE' COMMENT '因子状态: CANDIDATE/MAIN_FACTOR/SECONDARY/REJECTED',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_factor_id` (`factor_id`),
  KEY `idx_task_node_rank` (`task_id`, `node_id`, `rank`),
  KEY `idx_task_main` (`task_id`, `is_main`, `status`),
  KEY `idx_ds_dimension` (`task_id`, `dimension`, `segment_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归因候选因子表';

-- ============================================================
-- [rca-attribution-workflow] rcagent_attribution_query_log
-- ============================================================
CREATE TABLE IF NOT EXISTS `rcagent_attribution_query_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `query_id` VARCHAR(64) NOT NULL COMMENT '归因查询业务ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源实例ID',
  `task_id` VARCHAR(64) NOT NULL COMMENT '归属归因任务',
  `node_id` VARCHAR(64) NOT NULL COMMENT '归属下钻节点',
  `query_type` VARCHAR(32) NOT NULL COMMENT '查询类型: OVERALL/TREND/BASELINE/GROUP_CUR/GROUP_BASE/CROSS_GROUP/QUALITY_PROBE',
  `sql_text` TEXT NOT NULL COMMENT '归因SQL原文(受控存储)',
  `sql_abstract` VARCHAR(2000) NOT NULL COMMENT 'SQL脱敏抽象',
  `involved_tables` TEXT COMMENT '涉及表JSON数组',
  `risk_level` VARCHAR(16) DEFAULT NULL COMMENT '风险分级: BLOCK/LOW/MEDIUM/HIGH',
  `verdict` VARCHAR(16) DEFAULT NULL COMMENT '判定: ALLOW/REVIEW/BLOCK',
  `review_status` VARCHAR(16) NOT NULL DEFAULT 'NONE' COMMENT '人工审核状态: NONE/APPROVED/REJECTED',
  `executed` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已只读执行',
  `result_json` TEXT COMMENT '执行结果快照JSON',
  `result_check_json` TEXT COMMENT 'L5结果合理性信号JSON',
  `fix_suggestion` VARCHAR(500) DEFAULT NULL COMMENT '验证失败修复建议',
  `err_code` VARCHAR(32) DEFAULT NULL COMMENT '失败错误码',
  `err_msg` VARCHAR(500) DEFAULT NULL COMMENT '失败信息摘要',
  `cost_ms` INT DEFAULT NULL COMMENT '执行耗时',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_query_id` (`query_id`),
  KEY `idx_task_node` (`task_id`, `node_id`),
  KEY `idx_task_type` (`task_id`, `query_type`),
  KEY `idx_review` (`task_id`, `review_status`),
  KEY `idx_ds_time` (`ds_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归因查询日志表';

-- ============================================================
-- [rca-attribution-workflow] rcagent_attribution_review_log
-- ============================================================
CREATE TABLE IF NOT EXISTS `rcagent_attribution_review_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `review_id` VARCHAR(64) NOT NULL COMMENT '审核记录业务ID',
  `task_id` VARCHAR(64) NOT NULL COMMENT '归属归因任务',
  `thread_id` VARCHAR(64) DEFAULT NULL COMMENT '关联工作流会话',
  `review_object` VARCHAR(16) NOT NULL COMMENT '审核对象: QUERY/CONCLUSION',
  `query_id` VARCHAR(64) DEFAULT NULL COMMENT 'QUERY审核时对应query_id',
  `risk_level` VARCHAR(16) DEFAULT NULL COMMENT '被审核对象原始风险等级',
  `decision` VARCHAR(16) NOT NULL COMMENT '审核决策: APPROVE/REJECT',
  `comment` VARCHAR(1000) DEFAULT NULL COMMENT '审核意见(打回必填)',
  `reviewer_id` BIGINT NOT NULL COMMENT '审核人ID',
  `review_round` INT NOT NULL DEFAULT 1 COMMENT '第几轮审核',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_review_id` (`review_id`),
  KEY `idx_task_object` (`task_id`, `review_object`, `decision`),
  KEY `idx_reviewer` (`reviewer_id`, `create_time`),
  KEY `idx_thread` (`thread_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='归因审核留痕表';

-- ============================================================
-- [rcagent-workflow-engine] rcagent_workflow_metadata
-- ============================================================
CREATE TABLE IF NOT EXISTS `rcagent_workflow_metadata` (
    `thread_id`       VARCHAR(32)   NOT NULL COMMENT '会话线程ID(与Graph checkpoint一致)',
    `ds_id`           VARCHAR(64)   NOT NULL COMMENT '绑定的业务数据源ID(ds_config引用)',
    `question`        TEXT          COMMENT '用户原始问题',
    `intent_type`     VARCHAR(32)   DEFAULT NULL COMMENT '意图类型: CHAT-知识问答/QUERY-查数/ATTRIBUTION-归因追问',
    `status`          VARCHAR(32)   NOT NULL DEFAULT 'RUNNING' COMMENT '会话状态: RUNNING/WAITING_REVIEW/SENT_BACK/COMPLETED/TERMINATED/FAILED',
    `current_node`    VARCHAR(64)   DEFAULT NULL COMMENT '最近节点名(intent/retrieval/sql_generate/rcagent_review/execute)',
    `risk_level`      VARCHAR(16)   DEFAULT NULL COMMENT '风险分级快照: BLOCK/LOW/MEDIUM/HIGH',
    `review_decision` VARCHAR(32)   DEFAULT NULL COMMENT '最近审核决策: APPROVED/SENT_BACK/TERMINATED',
    `review_feedback` VARCHAR(1024) DEFAULT NULL COMMENT '审核备注/打回意见',
    `result_summary`  TEXT          COMMENT '结果摘要(查询结果/归因结论摘要)',
    `creator_id`      BIGINT        NOT NULL COMMENT '发起会话用户ID',
    `remark`          VARCHAR(500)  DEFAULT NULL COMMENT '备注(终止/失败原因)',
    `create_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`thread_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_creator_time` (`creator_id`, `create_time`),
    INDEX `idx_ds_id` (`ds_id`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RCAgent会话元数据表';

-- ============================================================
-- [rcagent-workflow-engine] rcagent_review_audit
-- ============================================================
CREATE TABLE IF NOT EXISTS `rcagent_review_audit` (
    `id`           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `thread_id`    VARCHAR(32)   NOT NULL COMMENT '会话线程ID(逻辑关联 rcagent_workflow_metadata)',
    `review_object` VARCHAR(32)  NOT NULL DEFAULT 'SQL' COMMENT '审核对象: SQL/ATTRIBUTION',
    `risk_level`   VARCHAR(16)   DEFAULT NULL COMMENT '风险等级快照: LOW/MEDIUM/HIGH/BLOCK',
    `sql_draft`    MEDIUMTEXT    COMMENT 'SQL草案原文(review_object=SQL时)',
    `conclusion`   MEDIUMTEXT    COMMENT '归因结论原文(review_object=ATTRIBUTION时)',
    `decision`     VARCHAR(32)   NOT NULL COMMENT '审核决策: APPROVED/SENT_BACK/TERMINATED',
    `comment`      VARCHAR(1024) DEFAULT NULL COMMENT '审核意见',
    `reviewer_id`  BIGINT        DEFAULT NULL COMMENT '审核人ID',
    `create_time`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审核时间',
    PRIMARY KEY (`id`),
    INDEX `idx_thread_id` (`thread_id`),
    INDEX `idx_object_decision` (`review_object`, `decision`),
    INDEX `idx_reviewer` (`reviewer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RCAgent人工审核审计表';

-- ============================================================
-- [multi-datasource-admin] t_ds_config_admin
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_ds_config_admin` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源标识(逻辑关联t_ds_config.ds_id)',
  `manage_state` VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '管理状态:DRAFT/ACTIVE/DISABLED/DELETED',
  `space_bind_state` VARCHAR(16) NOT NULL DEFAULT 'NOT_APPLIED' COMMENT '空间绑定态:NOT_APPLIED/BOUND/BIND_PENDING/BIND_FAILED',
  `space_bind_error` VARCHAR(512) DEFAULT NULL COMMENT '空间绑定失败原因摘要(脱敏)',
  `last_health_state` VARCHAR(16) NOT NULL DEFAULT 'UNCHECKED' COMMENT '最近健康:UNCHECKED/PASS/FAIL',
  `last_health_time` DATETIME DEFAULT NULL COMMENT '最近健康检查时间',
  `last_health_summary` TEXT COMMENT '最近健康摘要JSON(脱敏)',
  `creator_id` BIGINT NOT NULL COMMENT '创建操作人',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0=正常 1=删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ds_id` (`ds_id`),
  KEY `idx_manage_state` (`manage_state`),
  KEY `idx_space_bind_state` (`space_bind_state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源管理扩展(状态机/空间绑定/最近健康)';

-- ============================================================
-- [multi-datasource-admin] t_ds_config_audit
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_ds_config_audit` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) NOT NULL COMMENT '数据源标识',
  `action` VARCHAR(32) NOT NULL COMMENT '动作:REGISTER/UPDATE/ENABLE/DISABLE/DELETE/TEST_CONNECTION/HEALTH_CHECK/SPACE_BIND',
  `operator_id` BIGINT NOT NULL COMMENT '操作人ID',
  `operator_name` VARCHAR(64) DEFAULT NULL COMMENT '操作人显示名',
  `change_summary` VARCHAR(512) DEFAULT NULL COMMENT '变更摘要(不含密码值)',
  `change_detail` TEXT COMMENT '变更明细JSON(敏感字段只记changed标记不记值)',
  `biz_result` VARCHAR(16) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAIL',
  `error_message` VARCHAR(512) DEFAULT NULL COMMENT '失败原因(脱敏)',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '审计时间',
  PRIMARY KEY (`id`),
  KEY `idx_ds_action_time` (`ds_id`,`action`,`create_time`),
  KEY `idx_operator_time` (`operator_id`,`create_time`),
  KEY `idx_action_time` (`action`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源变更审计';

-- ============================================================
-- [multi-datasource-admin] t_ds_config_health
-- ============================================================
CREATE TABLE IF NOT EXISTS `t_ds_config_health` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `ds_id` VARCHAR(64) DEFAULT NULL COMMENT '数据源标识(测试连接未注册可空)',
  `check_type` VARCHAR(32) NOT NULL COMMENT 'HEALTH_CHECK/TEST_CONNECTION',
  `check_result` VARCHAR(16) NOT NULL COMMENT 'PASS/FAIL/PARTIAL',
  `dialect_identified` VARCHAR(32) DEFAULT NULL COMMENT '方言识别结果',
  `connection_ok` TINYINT NOT NULL DEFAULT 0 COMMENT '连通性:1=OK 0=FAIL',
  `readonly_ok` TINYINT NOT NULL DEFAULT 0 COMMENT '只读校验:1=OK 0=FAIL',
  `dictionary_ok` TINYINT NOT NULL DEFAULT 0 COMMENT '字典权限:1=OK 0=FAIL',
  `whitelist_ok` TINYINT NOT NULL DEFAULT 1 COMMENT '白名单满足:1=OK 0=FAIL',
  `failure_items` TEXT COMMENT '失败项枚举JSON数组',
  `error_message` VARCHAR(512) DEFAULT NULL COMMENT '脱敏失败原因',
  `cost_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '耗时ms',
  `operator_id` BIGINT NOT NULL COMMENT '触发人',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '检查时间',
  PRIMARY KEY (`id`),
  KEY `idx_ds_time` (`ds_id`,`create_time`),
  KEY `idx_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源健康检查历史';
