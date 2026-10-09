package com.agenthub.ai.rcagent.engine.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * rcagent 模块独立 Mapper 扫描配置。
 *
 * <p>brownfield 约束：不改存量 base/config/MyBatisPlusConfig 扫描包，
 * 单独追加 com.agenthub.ai.rcagent.engine.mapper；多 @MapperScan 由 Spring 按包合并，无冲突
 * （与 feedback/config/FeedbackMybatisConfig、kb/config/KbMybatisConfig 同款模式）。</p>
 */
@Configuration
@MapperScan("com.agenthub.ai.rcagent.engine.mapper")
public class RcaMybatisConfig {
}
