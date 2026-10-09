-- =============================================================
-- dual-retrieval 模块建表脚本（新增独立脚本，不改写存量 init.sql）
-- 表：rcagent_retrieval_log（双重知识库融合检索-质量观测日志）
-- 幂等：IF NOT EXISTS；随部署执行
-- 对齐：.aspirecode/sdd/rca1.0.0.0/dual-retrieval/data-model.md DDL
-- =============================================================

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
