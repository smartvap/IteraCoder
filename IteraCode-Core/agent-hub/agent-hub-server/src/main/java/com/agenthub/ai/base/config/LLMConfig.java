package com.agenthub.ai.base.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
@Configuration
public class LLMConfig {

    @Value("${spring.ai.ollama.base-url}")
    private String ollamaBaseUrl;

    @Value("#{${spring.ai.ollama.models}}")
    private Map<String, String> modelNames;

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashscopeApiKey;

    private OllamaApi ollamaApi() {
        return OllamaApi.builder()
                .baseUrl(ollamaBaseUrl)
                .build();
    }

    private OllamaChatModel createChatModel(String modelName, double temperature, int maxTokens) {
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .build();

        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi())
                .defaultOptions(options)
                .build();
    }

    @Bean("gemma2ChatModel")
    @Primary
    public ChatModel gemma2ChatModel() {
        OllamaChatModel delegate = createChatModel(modelNames.get("gemma2"), 0.7, 512);
        return new LoggingChatModel(delegate, "gemma2");
    }

    @Bean("gemma2ChatClient")
    @Primary
    public ChatClient gemma2ChatClient() {
        return ChatClient.builder(gemma2ChatModel()).build();
    }

    @Bean("qwenChatModel")
    public ChatModel qwenChatModel() {
        OllamaChatModel delegate = createChatModel(modelNames.get("qwen3"), 0.5, 1024);
        return new LoggingChatModel(delegate, "qwen3");
    }

    @Bean("qwenChatClient")
    public ChatClient qwenChatClient() {
        return ChatClient.builder(qwenChatModel()).build();
    }

    @Bean("dashscopeDeepseekChatModel")
    @ConditionalOnProperty(name = "spring.ai.dashscope.api-key", matchIfMissing = false)
    public DashScopeChatModel dashscopeDeepseekChatModel() {
        if (dashscopeApiKey == null || dashscopeApiKey.isEmpty()) {
            throw new IllegalStateException("DashScope API Key 未配置，请在 application.yml 中配置 spring.ai.dashscope.api-key");
        }
        return DashScopeChatModel.builder()
                .dashScopeApi(DashScopeApi.builder()
                        .apiKey(dashscopeApiKey)
                        .build())
                .defaultOptions(DashScopeChatOptions.builder()
                        .model("deepseek-v4-pro")
                        .temperature(0.7)
                        .build())
                .build();
    }

    @Bean("dashscopeDeepseekChatClient")
    @ConditionalOnProperty(name = "spring.ai.dashscope.api-key", matchIfMissing = false)
    public ChatClient dashscopeDeepseekChatClient() {
        return ChatClient.builder(dashscopeDeepseekChatModel()).build();
    }

    @Bean("dashscopeQwenMaxChatModel")
    @ConditionalOnProperty(name = "spring.ai.dashscope.api-key", matchIfMissing = false)
    public DashScopeChatModel dashscopeQwenMaxChatModel() {
        if (dashscopeApiKey == null || dashscopeApiKey.isEmpty()) {
            throw new IllegalStateException("DashScope API Key 未配置，请在 application.yml 中配置 spring.ai.dashscope.api-key");
        }
        return DashScopeChatModel.builder()
                .dashScopeApi(DashScopeApi.builder()
                        .apiKey(dashscopeApiKey)
                        .build())
                .defaultOptions(DashScopeChatOptions.builder()
                        .model("qwen-max")
                        .temperature(0.5)
                        .build())
                .build();
    }

    @Bean("dashscopeQwenMaxChatClient")
    @ConditionalOnProperty(name = "spring.ai.dashscope.api-key", matchIfMissing = false)
    public ChatClient dashscopeQwenMaxChatClient() {
        return ChatClient.builder(dashscopeQwenMaxChatModel()).build();
    }

    /**
     * ChatModel 包装类：拦截 stream/call，打印发给 Ollama 的完整 Prompt（含合并后的 Options）
     */
    static class LoggingChatModel implements ChatModel, StreamingChatModel {

        private final OllamaChatModel delegate;
        private final String label;

        LoggingChatModel(OllamaChatModel delegate, String label) {
            this.delegate = delegate;
            this.label = label;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ChatOptions merged = delegate.getDefaultOptions();
            log.info("[Ollama][{}] call | 模型={} | 合并选项={} | 消息数={}",
                    label,
                    merged != null ? ((OllamaChatOptions) merged).getModel() : "?",
                    merged,
                    prompt.getInstructions().size());
            for (int i = 0; i < prompt.getInstructions().size(); i++) {
                var msg = prompt.getInstructions().get(i);
                log.info("[Ollama][{}]   msg[{}] type={} text={}", label, i,
                        msg.getMessageType(),
                        msg.getText().length() > 500 ? msg.getText().substring(0, 500) + "..." : msg.getText());
            }
            return delegate.call(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            ChatOptions merged = delegate.getDefaultOptions();
            log.info("[Ollama][{}] stream | 模型={} | 合并选项={} | 消息数={}",
                    label,
                    merged != null ? ((OllamaChatOptions) merged).getModel() : "?",
                    merged,
                    prompt.getInstructions().size());
            for (int i = 0; i < prompt.getInstructions().size(); i++) {
                var msg = prompt.getInstructions().get(i);
                log.info("[Ollama][{}]   msg[{}] type={} text={}", label, i,
                        msg.getMessageType(),
                        msg.getText().length() > 500 ? msg.getText().substring(0, 500) + "..." : msg.getText());
            }
            return delegate.stream(prompt);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return delegate.getDefaultOptions();
        }
    }
}
