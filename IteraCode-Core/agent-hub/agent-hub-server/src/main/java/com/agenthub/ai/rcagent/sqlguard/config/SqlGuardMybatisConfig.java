package com.agenthub.ai.rcagent.sqlguard.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * sqlguard 模块独立 Mapper 扫描配置。
 *
 * <p>brownfield 约束：不改存量 base/config/MyBatisPlusConfig 扫描包，
 * 单独追加 com.agenthub.ai.rcagent.sqlguard.mapper；多 @MapperScan 由 Spring 按包合并，无冲突。</p>
 */
@Configuration
@MapperScan("com.agenthub.ai.rcagent.sqlguard.mapper")
public class SqlGuardMybatisConfig {
}
