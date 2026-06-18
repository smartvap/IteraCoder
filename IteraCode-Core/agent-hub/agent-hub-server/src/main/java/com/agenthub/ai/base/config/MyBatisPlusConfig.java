package com.agenthub.ai.base.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.agenthub.ai.base.mapper")
public class MyBatisPlusConfig {
}