package com.agenthub.ai.base.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "spring.ai")
@Data
public class ModelConfigProperties {

    private Ollama ollama = new Ollama();
    private DashScope dashscope = new DashScope();
    private OpenAi openai = new OpenAi();
    /** 主模型名称，必须与 models 列表中某个 name 一致；未配置则取列表第一个 */
    private String primary;
    private List<ModelConfig> models = new ArrayList<>();

    @Data
    public static class Ollama {
        private String baseUrl;
        private String timeout;
    }

    @Data
    public static class DashScope {
        private String apiKey;
    }

    @Data
    public static class OpenAi {
        private String apiKey;
        private String baseUrl;
    }

    @Data
    public static class ModelConfig {
        /** 模型别名，同时作为 Map key 用于 Controller 路由，如 "gemma2"、"deepseek-v4-pro" */
        private String name;
        /** 模型类型: ollama 或 dashscope */
        private String type;
        /** 实际模型名称，如 "gemma2:2b"、"deepseek-v4-pro" */
        private String model;
        /** 温度参数，默认 0.7 */
        private double temperature = 0.7;
        /** 最大 token 数，默认 2048 */
        private int maxTokens = 2048;
        /** 远程 API 地址，openai 兼容类型使用 */
        private String baseUrl;
        /** 远程 API 密钥，openai 兼容类型使用 */
        private String apiKey;
        /** completions 路径，如智谱 GLM 用 /v4/chat/completions，不配则用默认 /v1/chat/completions */
        private String completionsPath;
        /** Ollama 上下文窗口大小 */
        private Integer numCtx;
        /** Ollama GPU 层数 */
        private Integer numGPU;
        /** Ollama 批处理大小 */
        private Integer numBatch;
    }
}
