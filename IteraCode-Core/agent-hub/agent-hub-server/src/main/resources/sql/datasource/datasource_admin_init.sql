-- ============================================================
-- multi-datasource-admin 数据源注册表管理初始化（datasource_admin_init.sql）
-- 目标库: ai_server (MySQL 8, utf8mb4)
-- 特点: CREATE TABLE IF NOT EXISTS 幂等，可重复执行；不含 DROP/TRUNCATE；
--       本模块新增三张 t_ds_* 管理表；t_ds_config 主表由 module-002 db-access-common 定义，
--       若 ai_server 尚未建表则在此幂等兜底（与 rcagent_init.sql [db-access-common] 段一致）。
-- ============================================================

-- ============================================================
-- [db-access-common 引用] t_ds_config 主表（module-002 DDL，幂等兜底）
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
-- [multi-datasource-admin] t_ds_config_admin 管理扩展表
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
-- [multi-datasource-admin] t_ds_config_audit 变更审计表
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
-- [multi-datasource-admin] t_ds_config_health 健康检查历史表
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
