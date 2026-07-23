package com.agenthub.ai.base.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan({"com.agenthub.ai.base.mapper", "com.agenthub.ai.workflow.mapper"})
public class MyBatisPlusConfig {
}