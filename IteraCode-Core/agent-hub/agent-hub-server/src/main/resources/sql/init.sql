
-- ----------------------------
-- Table structure for tb_user
-- ----------------------------
DROP TABLE IF EXISTS `tb_user`;
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
) ENGINE=InnoDB AUTO_INCREMENT=666498 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ----------------------------
-- Records of tb_user
-- ----------------------------
INSERT INTO `tb_user` (`id`, `name`, `user_name`, `password`, `phone`, `sex`, `id_number`, `status`, `create_time`, `update_time`, `create_user`, `update_user`)
VALUES (666497, '管理员', 'admin', '21232f297a57a5a743894a0e4a801fc3', '13700138012', '男', '11010519721231002X', 1, '2026-06-17', '2026-06-17', NULL, NULL);



-- ----------------------------
-- Table structure for log_info
-- ----------------------------
DROP TABLE IF EXISTS `log_info`;
CREATE TABLE `log_info` (
                            `id` BIGINT NOT NULL AUTO_INCREMENT,
                            `method_name` VARCHAR(255) COMMENT '方法名',
                            `class_name` VARCHAR(255) COMMENT '类目',
                            `request_time` DATE COMMENT '请求时间戳',
                            `request_params` TEXT COMMENT '请求参数',
                            `response` TEXT COMMENT '响应结果',
                            PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日志信息表';


CREATE TABLE IF NOT EXISTS `workflow_metadata` (
    `thread_id`       VARCHAR(32)  NOT NULL COMMENT '工作流线程ID',
    `requirement`     TEXT         COMMENT '研发需求原文',
    `review_feedback` VARCHAR(512) COMMENT '审核备注',
    `status`          VARCHAR(32)  NOT NULL COMMENT '工作流状态：RUNNING-运行中 WAITING_REVIEW-待审核 COMPLETED-已完成 TERMINATED-已终止 FAILED-失败',
    `create_time`     DATETIME     NOT NULL COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL COMMENT '最后更新时间',
    PRIMARY KEY (`thread_id`),
    INDEX `idx_status` (`status`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工作流元数据表';


