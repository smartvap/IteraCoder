package com.agenthub.ai.base.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据库表初始化器
 * <p>启动时自动检测 MySQL 数据库中所需的表是否存在，不存在则自动创建。
 * H2 数据库通过 init-h2.sql 初始化，不需要此组件。</p>
 */
@Slf4j
@Component
public class DatabaseInitializer implements ApplicationRunner {

    private final DataSource dataSource;
    private final String dbUrl;

    public DatabaseInitializer(DataSource dataSource,
                               @Value("${spring.datasource.url:}") String dbUrl) {
        this.dataSource = dataSource;
        this.dbUrl = dbUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (dbUrl == null || !dbUrl.contains(":mysql:")) {
            log.info("非 MySQL 数据源，跳过表初始化检查");
            return;
        }

        log.info("开始检查 MySQL 数据库表...");
        try (Connection conn = dataSource.getConnection()) {
            Map<String, String> tables = getTableDDL();
            for (Map.Entry<String, String> entry : tables.entrySet()) {
                String tableName = entry.getKey();
                String ddl = entry.getValue();
                if (tableExists(conn, tableName)) {
                    log.info("  [OK] 表已存在: {}", tableName);
                } else {
                    log.info("  [创建] 表不存在，正在创建: {}", tableName);
                    createTable(conn, ddl);
                    log.info("  [OK] 表创建成功: {}", tableName);
                }
            }
            log.info("数据库表初始化完成，共检查 {} 张表", tables.size());

            // 初始化管理员用户
            initAdminUser(conn);

            // 追加字段（兼容旧表）
            addColumnIfNotExists(conn, "workflow_metadata", "remark", "VARCHAR(500) DEFAULT NULL COMMENT '备注'");
        } catch (Exception e) {
            log.error("数据库表初始化失败: {}", e.getMessage(), e);
        }
    }

    private void initAdminUser(Connection conn) {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id FROM tb_user WHERE user_name = 'admin'");
            if (rs.next()) {
                log.info("  [OK] 管理员用户已存在");
                return;
            }
            stmt.execute("INSERT INTO tb_user (id, name, user_name, password, phone, sex, id_number, status, create_time, update_time) " +
                    "VALUES (666497, '管理员', 'admin', '21232f297a57a5a743894a0e4a801fc3', '13700138012', '男', '11010519721231002X', 1, NOW(), NOW())");
            log.info("  [OK] 管理员用户已创建: admin / admin");
        } catch (Exception e) {
            log.warn("管理员用户初始化失败: {}", e.getMessage());
        }
    }

    private boolean tableExists(Connection conn, String tableName) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1 FROM `" + tableName + "` LIMIT 1");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void createTable(Connection conn, String ddl) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }

    private void addColumnIfNotExists(Connection conn, String table, String column, String definition) {
        try (Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "'");
            if (rs.next() && rs.getInt(1) == 0) {
                stmt.execute("ALTER TABLE `" + table + "` ADD COLUMN `" + column + "` " + definition);
                log.info("  [OK] 已添加列 {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("添加列失败 {}.{}: {}", table, column, e.getMessage());
        }
    }

    /**
     * 所有需要自动创建的表 DDL（MySQL 语法）
     */
    private static Map<String, String> getTableDDL() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();

        map.put("tb_user", """
            CREATE TABLE `tb_user` (
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表'
            """);

        map.put("tb_user_config", """
            CREATE TABLE `tb_user_config` (
                `id` INT NOT NULL AUTO_INCREMENT,
                `user_id` INT NOT NULL COMMENT '用户ID',
                `config_json` TEXT COMMENT '配置JSON',
                `create_time` DATETIME COMMENT '创建时间',
                `update_time` DATETIME COMMENT '更新时间',
                PRIMARY KEY (`id`),
                UNIQUE KEY `uk_user_id` (`user_id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户配置表'
            """);

        map.put("log_info", """
            CREATE TABLE `log_info` (
                `id` BIGINT NOT NULL AUTO_INCREMENT,
                `method_name` VARCHAR(255) COMMENT '方法名',
                `class_name` VARCHAR(255) COMMENT '类目',
                `request_time` DATE COMMENT '请求时间戳',
                `request_params` TEXT COMMENT '请求参数',
                `response` TEXT COMMENT '响应结果',
                PRIMARY KEY (`id`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日志信息表'
            """);

        map.put("token_usage_detail", """
            CREATE TABLE `token_usage_detail` (
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token使用明细表'
            """);

        map.put("token_usage_summary", """
            CREATE TABLE `token_usage_summary` (
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Token使用汇总表'
            """);

        map.put("model_config", """
            CREATE TABLE `model_config` (
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型配置表'
            """);

        map.put("workflow_metadata", """
            CREATE TABLE `workflow_metadata` (
                `thread_id` VARCHAR(32) NOT NULL COMMENT '工作流线程ID',
                `requirement` TEXT COMMENT '研发需求原文',
                `review_feedback` VARCHAR(512) COMMENT '审核备注',
                `status` VARCHAR(32) NOT NULL COMMENT '工作流状态',
                `create_time` DATETIME NOT NULL COMMENT '创建时间',
                `update_time` DATETIME NOT NULL COMMENT '最后更新时间',
                `remark` VARCHAR(500) COMMENT '备注',
                PRIMARY KEY (`thread_id`),
                INDEX `idx_status` (`status`),
                INDEX `idx_create_time` (`create_time`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流元数据表'
            """);

        map.put("sensitive_word", """
            CREATE TABLE `sensitive_word` (
                `id` BIGINT NOT NULL AUTO_INCREMENT,
                `word` VARCHAR(200) NOT NULL COMMENT '敏感词',
                `category` VARCHAR(50) DEFAULT 'general' COMMENT '分类',
                `level` VARCHAR(20) DEFAULT 'block' COMMENT 'block拦截/warn警告',
                `enabled` TINYINT DEFAULT 1 COMMENT '是否启用',
                `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (`id`),
                UNIQUE KEY `uk_word` (`word`),
                INDEX `idx_category` (`category`),
                INDEX `idx_enabled` (`enabled`)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='敏感词表'
            """);

        return map;
    }
}
