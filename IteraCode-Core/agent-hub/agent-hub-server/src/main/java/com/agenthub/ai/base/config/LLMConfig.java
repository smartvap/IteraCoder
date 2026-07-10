package com.agenthub.ai.base.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
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
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(ModelConfigProperties.class)
public class LLMConfig {

    private final ModelConfigProperties modelConfigProperties;
    private final DefaultListableBeanFactory beanFactory;

    /**
     * 启动后动态注册所有 ChatModel 和 ChatClient Bean
     */
    @PostConstruct
    void registerModelBeans() {
        OllamaApi ollamaApi = buildOllamaApi();
        DashScopeApi dashScopeApi = buildDashScopeApi();

        for (ModelConfigProperties.ModelConfig mc : modelConfigProperties.getModels()) {
            String name = mc.getName();
            if (name == null || name.isEmpty()) {
                log.warn("跳过无效模型配置: name 为空");
                continue;
            }

            ChatModel chatModel;
            if ("ollama".equalsIgnoreCase(mc.getType())) {
                chatModel = createOllamaChatModel(ollamaApi, mc);
            } else if ("dashscope".equalsIgnoreCase(mc.getType())) {
                if (dashScopeApi == null) {
                    log.warn("跳过 DashScope 模型 '{}': API Key 未配置", name);
                    continue;
                }
                chatModel = createDashScopeChatModel(dashScopeApi, mc);
            } else {
                log.warn("跳过未知类型的模型 '{}': type={}", name, mc.getType());
                continue;
            }

            // 1. 注册 ChatModel Bean → "{name}ChatModel"（可单独 @Qualifier 注入）
            beanFactory.registerSingleton(name + "ChatModel", chatModel);

            // 2. 注册 ChatClient Bean → "{name}"（Spring Map<String, ChatClient> 自动收集）
            ChatClient chatClient = ChatClient.builder(chatModel).build();
            beanFactory.registerSingleton(name, chatClient);

            log.info("注册模型: name={}, type={}, model={}, temperature={}",
                    name, mc.getType(), mc.getModel(), mc.getTemperature());
        }

        // 3. 设置 Primary ChatModel（先精确匹配配置的 primary 名，否则取第一个）
        String primaryName = modelConfigProperties.getPrimary();
        if (primaryName != null && !primaryName.isEmpty()) {
            Object singleton = beanFactory.getSingleton(primaryName + "ChatModel");
            if (singleton instanceof ChatModel cm) {
                beanFactory.registerResolvableDependency(ChatModel.class, cm);
                log.info("主模型: {} (由 spring.ai.primary 指定)", primaryName);
                return;
            }
            log.warn("配置的 primary={} 在 models 列表中未找到，回退到第一个模型", primaryName);
        }
        // 回退
        if (!modelConfigProperties.getModels().isEmpty()) {
            String firstName = modelConfigProperties.getModels().get(0).getName();
            Object singleton = beanFactory.getSingleton(firstName + "ChatModel");
            if (singleton instanceof ChatModel cm) {
                beanFactory.registerResolvableDependency(ChatModel.class, cm);
                log.info("主模型(回退): {} (未配置 spring.ai.primary，取第一个)", firstName);
            }
        }
    }

    // ===== 私有工厂方法 =====

    private OllamaApi buildOllamaApi() {
        String baseUrl = modelConfigProperties.getOllama().getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = "http://localhost:11434";
        }
        return OllamaApi.builder().baseUrl(baseUrl).build();
    }

    private DashScopeApi buildDashScopeApi() {
        String apiKey = modelConfigProperties.getDashscope().getApiKey();
        if (apiKey == null || apiKey.isEmpty() || apiKey.contains("$")) {
            return null;
        }
        return DashScopeApi.builder().apiKey(apiKey).build();
    }

    private ChatModel createOllamaChatModel(OllamaApi ollamaApi, ModelConfigProperties.ModelConfig mc) {
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(mc.getModel())
                .temperature(mc.getTemperature())
                .build();
        OllamaChatModel delegate = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(options)
                .build();
        return new LoggingChatModel(delegate, mc.getName());
    }

    private ChatModel createDashScopeChatModel(DashScopeApi api, ModelConfigProperties.ModelConfig mc) {
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .defaultOptions(DashScopeChatOptions.builder()
                        .model(mc.getModel())
                        .temperature(mc.getTemperature())
                        .maxToken(mc.getMaxTokens())
                        .build())
                .build();
    }

    // ===== LoggingChatModel 内部类 =====

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
                    merged != null ? (merged instanceof OllamaChatOptions o ? o.getModel() : "?") : "?",
                    merged,
                    prompt.getInstructions().size());
            for (int i = 0; i < prompt.getInstructions().size(); i++) {
                var msg = prompt.getInstructions().get(i);
                log.info("[Ollama][{}]   msg[{}] type={} text={}", label, i,
                        msg.getMessageType(),
                        msg.getText().length() > 1000 ? msg.getText().substring(0, 1000) + "..." : msg.getText());
            }
            return delegate.call(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            ChatOptions merged = delegate.getDefaultOptions();
            log.info("[Ollama][{}] stream | 模型={} | 合并选项={} | 消息数={}",
                    label,
                    merged != null ? (merged instanceof OllamaChatOptions o ? o.getModel() : "?") : "?",
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
