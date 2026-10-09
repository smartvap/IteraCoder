package com.agenthub.ai.rcagent.attribution.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * attribution 模块独立 Mapper 扫描配置。
 *
 * <p>存量 base/config/MyBatisPlusConfig 已扫描 base.mapper 与 workflow.mapper，
 * kb/retrieval/sqlguard 各模块均按 brownfield 约束使用独立 @MapperScan；
 * 本模块同样不改存量扫描，单独追加 attribution.mapper 扫描（多 @MapperScan 由 Spring 按包合并）。</p>
 */
@Configuration
@MapperScan("com.agenthub.ai.rcagent.attribution.mapper")
public class AttributionMybatisConfig {
}
