-- =====================================================
-- MySQL 自动初始化脚本（项目启动时自动执行）
-- 使用 CREATE TABLE IF NOT EXISTS，安全可重复执行
-- =====================================================

-- ----------------------------
-- Table structure for tb_user
-- ----------------------------
CREATE TABLE IF NOT EXISTS `tb_user` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `name` VARCHAR(255) NOT NULL COMMENT '姓名',
    `user_name` VARCHAR(255) NOT NULL COMMENT '用户名',
    `password` VARCHAR(255) NOT NULL COMMENT '密码',
    `phone` VARCHAR(255) NOT NULL COMMENT '手机号',
    `sex` VARCHAR(255) NOT NULL COMMENT '性别',
    `id_number` VARCHAR(255) NOT NULL COMMENT '身份证号',
    `status` INT NOT NULL DEFAULT 1 COMMENT '状态 0：禁用 1：启用',
    `create_time` DATE COMMENT '创建时间',
    `update_time` DATE COMMENT '更新时间',
    `create_user` BIGINT COMMENT '创建人',
    `update_user` BIGINT COMMENT '修改人',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=666498 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- 初始化管理员（如不存在）
INSERT IGNORE INTO `tb_user` (`id`, `name`, `user_name`, `password`, `phone`, `sex`, `id_number`, `status`, `create_time`, `update_time`, `create_user`, `update_user`)
VALUES (666497, '管理员', 'admin', '21232f297a57a5a743894a0e4a801fc3', '13700138012', '男', '11010519721231002X', 1, '2026-06-17', '2026-06-17', NULL, NULL);

-- ----------------------------
-- Table structure for tb_user_config
-- ----------------------------
CREATE TABLE IF NOT EXISTS `tb_user_config` (
    `id` INT NOT NULL AUTO_INCREMENT,
    `user_id` INT NOT NULL COMMENT '用户ID',
    `config_json` TEXT COMMENT '配置JSON',
    `create_time` DATETIME COMMENT '创建时间',
    `update_time` DATETIME COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户配置表';

-- ----------------------------
-- Table structure for log_info
-- ----------------------------
CREATE TABLE IF NOT EXISTS `log_info` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `method_name` VARCHAR(255) COMMENT '方法名',
    `class_name` VARCHAR(255) COMMENT '类目',
    `request_time` DATE COMMENT '请求时间戳',
    `request_params` TEXT COMMENT '请求参数',
    `response` TEXT COMMENT '响应结果',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日志信息表';

-- ----------------------------
-- Table structure for token_usage_detail
-- ----------------------------
CREATE TABLE IF NOT EXISTS `token_usage_detail` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NULL COMMENT '登录用户ID（NULL=匿名）',
    `ip_address` VARCHAR(45) NOT NULL COMMENT '客户端真实IP',
    `model_name` VARCHAR(100) NOT NULL COMMENT '模型名称',
    `prompt_tokens` INT NOT NULL DEFAULT 0 COMMENT '输入token数',
    `completion_tokens` INT NOT NULL DEFAULT 0 COMMENT '输出token数',
    `total_duration_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '总耗时(ms)',
    `request_time` DATETIME NOT NULL COMMENT '请求时间',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1=成功 0=失败',
    PRIMARY KEY (`id`),
    INDEX `idx_user_ip_time` (`user_id`, `ip_address`, `request_time`),
    INDEX `idx_ip_time` (`ip_address`, `request_time`),
    INDEX `idx_request_time` (`request_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token使用明细表';

-- ----------------------------
-- Table structure for token_usage_summary
-- ----------------------------
CREATE TABLE IF NOT EXISTS `token_usage_summary` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `stat_type` VARCHAR(20) NOT NULL COMMENT 'user_ip / ip_only',
    `stat_key` VARCHAR(100) NOT NULL COMMENT 'userId_ip 或 ip',
    `stat_date` DATE NOT NULL COMMENT '统计日期',
    `total_requests` INT NOT NULL DEFAULT 0,
    `total_prompt_tokens` BIGINT NOT NULL DEFAULT 0,
    `total_completion_tokens` BIGINT NOT NULL DEFAULT 0,
    `total_duration_ms` BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_stat` (`stat_type`, `stat_key`, `stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Token使用汇总表';

-- ----------------------------
-- Table structure for model_config
-- ----------------------------
CREATE TABLE IF NOT EXISTS `model_config` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NULL COMMENT '登录用户ID',
    `ip_address` VARCHAR(45) NOT NULL COMMENT '客户端IP',
    `config_name` VARCHAR(100) NOT NULL COMMENT '配置名称',
    `model_type` VARCHAR(20) NOT NULL COMMENT 'ollama/openai/dashscope',
    `model_name` VARCHAR(100) NOT NULL COMMENT '实际模型名',
    `base_url` VARCHAR(500) NULL COMMENT 'API地址',
    `api_key` VARCHAR(500) NULL COMMENT 'API Key',
    `temperature` DOUBLE DEFAULT 0.7,
    `max_tokens` INT DEFAULT 4096,
    `is_active` TINYINT DEFAULT 1,
    `create_time` DATETIME NOT NULL,
    `update_time` DATETIME NOT NULL,
    PRIMARY KEY (`id`),
    INDEX `idx_user_ip` (`user_id`, `ip_address`),
    INDEX `idx_ip` (`ip_address`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型配置表';

-- ----------------------------
-- Table structure for conversation_record
-- ----------------------------
CREATE TABLE IF NOT EXISTS `conversation_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
    `user_id` BIGINT NULL COMMENT '用户ID',
    `role` VARCHAR(16) NOT NULL COMMENT '角色: user/assistant',
    `content` TEXT COMMENT '消息内容',
    `model_name` VARCHAR(64) NULL COMMENT '模型名称',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话记录表';

-- ----------------------------
-- Table structure for daily_token_stats
-- ----------------------------
CREATE TABLE IF NOT EXISTS `daily_token_stats` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `stat_date` DATE NOT NULL COMMENT '统计日期',
    `total_requests` INT NOT NULL DEFAULT 0 COMMENT '总请求次数',
    `total_prompt_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '总输入token数',
    `total_completion_tokens` BIGINT NOT NULL DEFAULT 0 COMMENT '总输出token数',
    `total_duration_ms` BIGINT NOT NULL DEFAULT 0 COMMENT '总耗时(ms)',
    `total_users` INT NOT NULL DEFAULT 0 COMMENT '活跃用户数',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_stat_date` (`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='每日Token累计统计表';

-- ----------------------------
-- Table structure for workflow_record
-- ----------------------------
CREATE TABLE IF NOT EXISTS `workflow_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `session_id` VARCHAR(64) NOT NULL COMMENT '会话ID',
    `thread_id` VARCHAR(128) NOT NULL COMMENT '工作流线程ID',
    `round` INT DEFAULT 1 COMMENT '轮次编号',
    `requirement` TEXT COMMENT '原始需求文本',
    `status` VARCHAR(32) COMMENT '状态: COMPLETED/TERMINATED/FAILED',
    `decomposition_result` MEDIUMTEXT COMMENT '需求拆解结果',
    `reasoning_result` MEDIUMTEXT COMMENT '并行推理结果',
    `review_decision` VARCHAR(32) COMMENT '审核决定: APPROVED/SENT_BACK/TERMINATED',
    `review_comment` TEXT COMMENT '审核备注',
    `codegen_files` TEXT COMMENT '代码生成文件列表(JSON数组)',
    `prompt_tokens` BIGINT DEFAULT 0 COMMENT '输入Token数',
    `completion_tokens` BIGINT DEFAULT 0 COMMENT '输出Token数',
    `total_duration_ms` BIGINT DEFAULT 0 COMMENT '总耗时(ms)',
    `workflow_message` TEXT COMMENT '工作流结束消息',
    `start_time` DATETIME COMMENT '开始时间',
    `end_time` DATETIME COMMENT '结束时间',
    `create_time` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_thread_id` (`thread_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流执行记录表';

-- =====================================================
-- 已有表字段升级（安全ALTER，含IF EXISTS/IF NOT EXISTS）
-- =====================================================
-- 为旧版 token_usage_detail 添加新字段（MySQL 不支持 IF NOT EXISTS for columns，使用存储过程兜底）
DROP PROCEDURE IF EXISTS add_column_if_not_exists;
DELIMITER $$
CREATE PROCEDURE add_column_if_not_exists(
    IN tbl VARCHAR(128), IN col VARCHAR(128), IN colDef VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE ', tbl, ' ADD COLUMN ', col, ' ', colDef);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL add_column_if_not_exists('token_usage_detail', 'source', "VARCHAR(32) DEFAULT 'chat' COMMENT '来源: chat/workflow/rag'");
CALL add_column_if_not_exists('token_usage_detail', 'step_name', "VARCHAR(100) DEFAULT NULL COMMENT '步骤名称'");

DROP PROCEDURE IF EXISTS add_column_if_not_exists;
