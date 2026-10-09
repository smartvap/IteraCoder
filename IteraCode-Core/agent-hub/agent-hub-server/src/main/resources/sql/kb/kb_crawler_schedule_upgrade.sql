-- ============================================================
-- kb-auto-maintain 升级脚本：kb_crawl_task 追加任务级 cron 调度
-- 目标库：MySQL 8 / ai_server
-- 特性：幂等（基于 information_schema 判存 + 动态 SQL），可重复执行
-- 不改动存量 kb_crawler_init.sql / rcagent_init.sql
-- 新增列：schedule_cron（Spring 6 段 cron，可空=用默认）
--         next_run_time（下次到点时间，可空=尚未建立调度基线）
-- 新增索引：idx_next_run_time
-- 来源：kb-auto-maintain data-model.md
-- ============================================================

-- ---------- 1. 追加 schedule_cron ----------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'kb_crawl_task'
    AND COLUMN_NAME = 'schedule_cron'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `kb_crawl_task` ADD COLUMN `schedule_cron` VARCHAR(64) DEFAULT NULL '
  'COMMENT ''任务级调度cron(Spring 6段:秒 分 时 日 月 周);NULL/空=用默认'' AFTER `remark`',
  'SELECT ''[skip] kb_crawl_task.schedule_cron already exists'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2. 追加 next_run_time ----------
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'kb_crawl_task'
    AND COLUMN_NAME = 'next_run_time'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE `kb_crawl_task` ADD COLUMN `next_run_time` DATETIME DEFAULT NULL '
  'COMMENT ''下次到点时间(NULL=尚未建立调度基线,首次扫描即视为到点)'' AFTER `schedule_cron`',
  'SELECT ''[skip] kb_crawl_task.next_run_time already exists'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 3. 追加 idx_next_run_time ----------
SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'kb_crawl_task'
    AND INDEX_NAME = 'idx_next_run_time'
);
SET @ddl := IF(@idx_exists = 0,
  'ALTER TABLE `kb_crawl_task` ADD INDEX `idx_next_run_time` (`next_run_time`)',
  'SELECT ''[skip] idx_next_run_time already exists'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 4. 遗留行回填（可选，推荐低峰执行） ----------
-- 说明：next_run_time 为 NULL 会被新调度器"视为到点并触发一次"（一次性自愈）。
-- 若希望避免升级后首轮集中触发，可执行下面语句把已 pause 的任务明确置为"待恢复"：
--   UPDATE `kb_crawl_task` SET `next_run_time` = NULL WHERE `pause_flag` = 1;  -- 无实际效果，仅表意
-- 若希望把遗留行统一推迟到 1 分钟后（平滑发布），可执行：
--   UPDATE `kb_crawl_task`
--      SET `next_run_time` = DATE_ADD(NOW(), INTERVAL 1 MINUTE)
--    WHERE `next_run_time` IS NULL;
--   （注意：这会跳过"升级后立即采集一次"的自愈行为，需按运维节奏二选一。）

-- ---------- 5. 结果自检 ----------
SELECT
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_crawl_task'
      AND COLUMN_NAME IN ('schedule_cron','next_run_time')) AS added_columns_expected_2,
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'kb_crawl_task'
      AND INDEX_NAME = 'idx_next_run_time') AS added_index_expected_1;
