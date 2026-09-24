package com.agenthub.ai.dbaccess.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * db-access-common 模块配置装配入口。
 *
 * <p>本模块作为库被 agent-hub-server 及 module-003/006/007/009 复用：调用方 Spring Boot 主类
 * 扫描 {@code com.agenthub.ai} 基包即可发现 {@code com.agenthub.ai.dbaccess} 下全部 @Service/@Component Bean，
 * 本配置类负责激活 {@link DbAccessProperties} 属性绑定（前缀 agenthub.dbaccess）。</p>
 */
@Configuration
@EnableConfigurationProperties(DbAccessProperties.class)
public class DbAccessModuleConfiguration {
}
