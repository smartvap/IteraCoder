package com.agenthub.ai.datasource.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * datasource 模块独立 Mapper 扫描配置。
 *
 * <p>存量 base/config/MyBatisPlusConfig 已扫描 base.mapper 与 workflow.mapper，
 * 本模块按 brownfield 约束<b>不修改存量扫描</b>，单独追加 com.agenthub.ai.datasource.mapper 扫描；
 * 多 @MapperScan 由 Spring 按包合并，无冲突（同 kb/kbcrawler 模块做法）。</p>
 */
@Configuration
@MapperScan("com.agenthub.ai.datasource.mapper")
public class DsMybatisConfig {
}
