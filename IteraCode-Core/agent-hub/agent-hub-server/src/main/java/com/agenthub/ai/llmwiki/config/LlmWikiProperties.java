package com.agenthub.ai.llmwiki.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * llm-wiki 知识库服务连接配置
 * <p>
 * 对应 application.yml：{@code agenthub.llm-wiki.*}
 */
@Data
@Component
@ConfigurationProperties(prefix = "agenthub.llm-wiki")
public class LlmWikiProperties {

    /** llm-wiki 服务基础地址（含 /api/v1 前缀），如 http://localhost:8000/api/v1 */
    private String baseUrl = "http://localhost:8000/api/v1";

    /** 连接超时（毫秒） */
    private long connectTimeoutMs = 3000;

    /** 读超时（毫秒）；检索/写入含 embedding 调用，放宽到 60s */
    private long readTimeoutMs = 60000;

    /** llm-wiki 上传专用读超时（毫秒）；文档 embedding 较慢需放宽 */
    private long uploadTimeoutMs = 300000;

    public String trimmedBaseUrl() {
        return (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
    }
}
