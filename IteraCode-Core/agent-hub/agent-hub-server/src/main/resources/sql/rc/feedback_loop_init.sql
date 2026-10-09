-- ============================================================
-- 反馈闭环与知识沉淀 feedback-loop 建表脚本（MySQL 8 / ai_server）
-- 文件名：agent-hub-server/src/main/resources/sql/feedback_loop_init.sql
-- 说明：kb_feedback / kb_review_task / kb_* 归 module-004，不在此建表；
--       本脚本幂等可重复执行（CREATE TABLE IF NOT EXISTS），不改动存量 init.sql。
-- ============================================================

-- 1. fb_feedback_flow 反馈闭环处理流
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

-- 2. fb_wiki_writeback B库出站任务
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
