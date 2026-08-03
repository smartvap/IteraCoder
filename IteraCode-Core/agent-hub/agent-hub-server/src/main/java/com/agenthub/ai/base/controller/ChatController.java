package com.agenthub.ai.base.controller;

import com.agenthub.ai.base.annotation.Loggable;
import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.context.BaseContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.context.ApplicationContext;



/**
 * @Title: ChatController
 *
 * @Package com.agenthub.ai.controller
 * @description: 对话接口
 */

@Tag(name="AiRagController",description = "chat对话接口")
@Slf4j
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/chat")
public class ChatController {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ollama 服务基础地址 */
    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    //    private final ChatClient chatClient;
    private final ChatClient.Builder chatClientBuilder;
    private final Map<String, ChatClient> chatClients;
    private final ChatMemory chatMemory;
    private final ApplicationContext applicationContext;

    public ChatController(ChatClient.Builder chatClientBuilder, Map<String, ChatClient> chatClients, ChatMemory chatMemory, ApplicationContext applicationContext) {
        this.chatClientBuilder = chatClientBuilder;
        this.chatClients = chatClients;
        this.chatMemory = chatMemory;
        this.applicationContext = applicationContext;

//        ChatOptions options = ChatOptions.builder()
//                .model("gemma2:2b")
//                .temperature(0.7)
//                .build();
//        this.chatClient = ChatClient.builder(chatModel)
//                .defaultOptions(options)
//                .defaultSystem("你是自动化研发智能体系统的客户客服代理...")
//                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
//                .build();
    }

//    public ChatController(ChatClient.Builder builder,ChatMemory chatMemory) {
//        ChatOptions options = ChatOptions.builder()
//                .model("gemma4:e4b")
//                .temperature(0.7)
//                .build();
//        this.chatClient = builder
//                .defaultOptions(options)
//                .defaultSystem("""
//                        你是自动化研发智能体系统的客户客服代理。请友好乐于助人，充满喜悦地回复。
//                        """)
//                .defaultAdvisors(
//                        MessageChatMemoryAdvisor.builder(chatMemory).build() // CHAT MEMORY
//
//                        )
//                .build();
//    }

    @Operation(summary = "stream",description = "流式对话接口")
    @GetMapping(value = "/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Loggable("message")
    public Flux<String> streamRagChat(@RequestParam(value = "model", defaultValue = "gemma4:12b" ) String model,
                                      @RequestParam(value = "message", defaultValue = "你好" ) String message,
                                      @RequestParam(value = "prompt", defaultValue = "你是一名自动化研发智能体系统助手，致力于帮助人们解决问题.") String prompt,
                                      @RequestParam(value = "apiKey", defaultValue = "") String apiKey,
                                      @RequestParam(value = "baseUrl", defaultValue = "") String baseUrl){

        Long userId = BaseContext.getCurrentId();

        log.info("streamRagChat: model={}, apiKey有值={}, baseUrl={}", model, apiKey != null && !apiKey.isEmpty(), baseUrl);
        ChatClient selectedClient = findChatClient(model, apiKey, baseUrl);
        return selectedClient.prompt()
                .system(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId))
                .user(message)
                .stream()
                .content();
    }

    private ChatClient findChatClient(String model, String apiKey, String baseUrl) {
        // 前端传了 apiKey → 动态创建（不走 YAML 配置）
        if (apiKey != null && !apiKey.isEmpty()) {
            log.info("使用前端提供的 apiKey 动态创建 ChatClient: model={}, baseUrl={}", model, baseUrl);
            var apiBuilder = org.springframework.ai.openai.api.OpenAiApi.builder().apiKey(apiKey);
            if (baseUrl != null && !baseUrl.isEmpty()) apiBuilder.baseUrl(baseUrl);
            apiBuilder.completionsPath("/chat/completions");
            return org.springframework.ai.chat.client.ChatClient.builder(
                    org.springframework.ai.openai.OpenAiChatModel.builder()
                            .openAiApi(apiBuilder.build())
                            .build())
                    .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder().model(model).build())
                    .build();
        }
        // 从注册的 ChatClient Map 中查找
        if (chatClients.containsKey(model)) {
            ChatClient client = chatClients.get(model);
            log.info("使用注册的 ChatClient: {}, 类型={}", model, client.getClass().getSimpleName());
            return client;
        }
        log.warn("模型 '{}' 未注册，回退到默认 Builder", model);
        return chatClientBuilder.clone()
                .defaultOptions(OllamaChatOptions.builder().model(model).temperature(0.7).build())
                .build();
    }



    @Operation(summary = "stream2",description = "流式对话接口(无data前缀)")
    @PostMapping(value = "/stream2", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Loggable("message")
    public Flux<String> streamRagChat2(
            @RequestParam(value = "model", defaultValue = "qwen3") String model,
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @RequestParam(value = "prompt", defaultValue = "你是一名自动化研发智能体系统助手，致力于帮助人们解决问题.请以中文回答问题") String prompt){

        log.info("收到流式请求 - message: {}, prompt: {}", message, prompt);

        Long userId = BaseContext.getCurrentId();
        ChatClient selectedClient = chatClients.get(model);
        if (selectedClient == null) {
            log.warn("模型 '{}' 未注册，回退到默认 Builder", model);
            selectedClient = chatClientBuilder.clone()
                    .defaultOptions(OllamaChatOptions.builder().model(model).temperature(0.7).build())
                    .build();
        }
        return selectedClient.prompt()
                .system(prompt)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId))
                .user(message)
                .stream()
                .content()
                .map(chunk -> chunk.replace("\n", "").replace("\r", ""));
    }


    /**
     * 获取可用模型列表
     *
     * @param apiUrl 远程 API 地址（为空则查询本地 Ollama）
     * @param apiKey 远程 API 密钥
     * @return 模型信息列表
     */
    @Operation(summary = "models", description = "获取模型列表(本地 Ollama 或远程 API)")
    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ChatController2.ModelInfo> listModels(
            @RequestParam(value = "apiUrl", defaultValue = "") String apiUrl,
            @RequestParam(value = "apiKey", defaultValue = "") String apiKey,
            @RequestParam(value = "ollamaUrl", defaultValue = "") String ollamaUrlParam) {
        if (apiUrl != null && !apiUrl.isEmpty()) {
            return fetchRemoteModels(apiUrl, apiKey);
        }
        log.info("===== listModels ollamaUrl param: '{}' =====", ollamaUrlParam);
        return fetchLocalOllamaModels(ollamaUrlParam.isEmpty() ? ollamaBaseUrl : ollamaUrlParam);
    }

    /**
     * 从本地 Ollama 获取模型列表
     *
     * <p>调用 Ollama {@code /api/tags} 端点获取已安装的模型。</p>
     *
     * @return 模型信息列表
     */
    private List<ChatController2.ModelInfo> fetchLocalOllamaModels(String ollamaUrl) {
        log.info("===== 获取 Ollama 模型列表 (url={}) =====", ollamaUrl);
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ollamaUrl.replaceAll("/$", "") + "/api/tags");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("Ollama 返回 " + conn.getResponseCode());
            }

            JsonNode root = JSON.readTree(conn.getInputStream());
            List<ChatController2.ModelInfo> result = new ArrayList<>();
            for (JsonNode m : root.get("models")) {
                String name = m.get("name").asText();
                String family = m.path("details").path("family").asText("");
                result.add(new ChatController2.ModelInfo(name, name, "ollama", family));
            }
            log.info("Ollama 获取到 {} 个模型", result.size());
            return result;
        } catch (Exception e) {
            log.warn("获取 Ollama 模型列表失败 ({}): {} — 返回空列表", ollamaUrl, e.getMessage());
            return Collections.emptyList();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 从远程 API 获取模型列表
     *
     * <p>调用远程 API 的 {@code /v1/models} 端点获取可用模型。</p>
     *
     * @param apiUrl 远程 API 基础地址
     * @param apiKey API 密钥
     * @return 模型信息列表
     */
    @SuppressWarnings("unchecked")
    private List<ChatController2.ModelInfo> fetchRemoteModels(String apiUrl, String apiKey) {
        log.info("===== 获取远程模型列表 {} =====", apiUrl);
        try {
            String baseUrl = apiUrl.replaceAll("/$", "");
            RestTemplate rest = new RestTemplate();
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            if (apiKey != null && !apiKey.isEmpty()) {
                headers.setBearerAuth(apiKey);
            }
            org.springframework.http.HttpEntity<?> entity = new org.springframework.http.HttpEntity<>(headers);

            ResponseEntity<Map<String, Object>> resp = rest.exchange(
                    baseUrl + "/v1/models",
                    org.springframework.http.HttpMethod.GET,
                    entity,
                    (Class<Map<String, Object>>) (Class<?>) Map.class);

            Map<String, Object> body = resp.getBody();
            if (body == null || !body.containsKey("data")) {
                log.warn("远程 API 返回格式异常: {}", body);
                return List.of();
            }

            List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
            List<ChatController2.ModelInfo> result = new ArrayList<>();
            for (Map<String, Object> m : data) {
                String id = (String) m.get("id");
                if (id != null) {
                    result.add(new ChatController2.ModelInfo(id, id, "remote", ""));
                }
            }

            log.info("远程 API 获取到 {} 个模型", result.size());
            return result;
        } catch (Exception e) {
            log.error("获取远程模型列表失败: {}", e.getMessage());
            throw new RuntimeException("无法连接远程 API: " + e.getMessage());
        }
    }

}
