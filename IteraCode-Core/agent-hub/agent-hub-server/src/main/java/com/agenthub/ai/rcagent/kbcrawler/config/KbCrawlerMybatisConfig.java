package com.agenthub.ai.rcagent.kbcrawler.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * kb-schema-crawler 模块独立 Mapper 扫描与配置装配。
 *
 * <p>存量 base/config/MyBatisPlusConfig 扫描 base.mapper 与 workflow.mapper，
 * module-004 kb 通过 KbMybatisConfig 追加 kb.mapper；本模块按 brownfield 约束
 * <b>不修改存量扫描</b>，独立追加 {@code com.agenthub.ai.rcagent.kbcrawler.mapper}，
 * 多 @MapperScan 由 Spring 按包合并，无冲突。</p>
 */
@Configuration
@EnableConfigurationProperties(KbCrawlerProperties.class)
@MapperScan("com.agenthub.ai.rcagent.kbcrawler.mapper")
public class KbCrawlerMybatisConfig {
}
