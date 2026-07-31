package com.agenthub.ai.base.controller;

import com.agenthub.ai.base.common.ApplicationConstant;
import com.agenthub.ai.base.common.BaseResponse;
import com.agenthub.ai.base.common.ResultUtils;
import com.agenthub.ai.base.logger.ConversationLogger;
import com.agenthub.ai.base.config.ModelConfigProperties;
import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.base.service.TokenUsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.SignalType;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 动态模型对话控制器
 *
 * <p>支持本地 Ollama 和远程 OpenAI 兼容 API 的流式对话。
 * 使用 WebFlux {@link Flux} + {@link ServerSentEvent} 实现 SSE 流式响应，
 * 通过 {@link Schedulers#boundedElastic()} 桥接阻塞 IO（HttpURLConnection）到响应式链路。</p>
 *
 * <h3>SSE 事件类型：</h3>
 * <ul>
 *   <li>{@code event:reasoning} — 模型思考过程（Ollama 的 thinking 字段或远程 API 的 reasoning_content）</li>
 *   <li>{@code event:content} — 模型正式回复内容</li>
 *   <li>{@code event:done} — 流结束标记，data 为 [DONE]</li>
 * </ul>
 *
 * <h3>参数传递方式：</h3>
 * <p>前端通过 URL query params 传递参数（Spring WebFlux 的 {@code @RequestParam} 不解析 form body）。</p>
 *
 * @see org.springframework.http.codec.ServerSentEvent
 * @see reactor.core.publisher.Flux
 */
@Tag(name = "ChatController2", description = "动态模型对话接口")
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping(ApplicationConstant.API_VERSION + "/chat2")
public class ChatController2 {

    /** JSON 序列化/反序列化工具 */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 流结束标记事件 */
    private static final ServerSentEvent<String> DONE_EVENT =
            ServerSentEvent.<String>builder().event("done").data("[DONE]").build();

    /** 多语言系统提示词映射：指导模型使用规范的 Markdown 格式输出 */
    private static final Map<String, String> SYSTEM_PROMPTS = Map.of(
        "zh-CN", "你是技术助手。回复要求：使用 ## 标题、```语言 标注代码、- 列表、**加粗**重点。",
        "en", "You are a technical assistant. Use ## headings, ```language code blocks, - lists, **bold** for emphasis.",
        "ja", "技術アシスタントです。見出しに ##、コードに ```言語、リストに -、強調に **太字** を使用。",
        "ko", "기술 어시스턴트입니다. ## 제목, ```언어 코드, - 목록, **굵게** 강조를 사용하세요.",
        "fr", "Assistant technique. Utilisez ## titres, ```langage code, - listes, **gras** pour accentuation.",
        "de", "Technischer Assistent. Verwenden Sie ## Überschriften, ```Sprache Code, - Listen, **fett** für Betonung."
    );

    /** Ollama 服务基础地址，从配置文件读取，默认 http://localhost:11434 */
    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    private final TokenUsageService tokenUsageService;
    private final ApplicationContext applicationContext;

    /**
     * 流式对话接口
     *
     * <p>根据 {@code isLocal} 参数决定调用本地 Ollama 还是远程 API。
     * 返回 {@code text/event-stream} 类型的 SSE 流。</p>
     *
     * @param model   模型名称，如 "gemma4-12b-local"
     * @param message 用户输入的消息内容
     * @param isLocal 是否调用本地 Ollama（true=本地，false=远程）
     * @param apiUrl  远程 API 地址（仅 isLocal=false 时使用）
     * @param apiKey  远程 API 密钥（仅 isLocal=false 时使用）
     * @return SSE 事件流，包含 reasoning/content/done 事件
     */
    @Operation(summary = "stream", description = "流式对话接口(支持本地/远程模型)")
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestParam(value = "model", defaultValue = "gemma4-12b-local") String model,
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @RequestParam(value = "isLocal", defaultValue = "true") boolean isLocal,
            @RequestParam(value = "ollamaUrl", defaultValue = "http://localhost:11434") String ollamaUrlParam,
            @RequestParam(value = "apiUrl", defaultValue = "") String apiUrl,
            @RequestParam(value = "apiKey", defaultValue = "") String apiKey,
            @RequestParam(value = "messages", defaultValue = "") String messagesJson,
            @RequestParam(value = "lang", defaultValue = "zh-CN") String lang,
            HttpServletRequest request) {

        long requestStart = System.currentTimeMillis();
        // 使用请求参数中的 Ollama URL，否则使用默认配置
        String effectiveOllamaUrl = ollamaUrlParam.isEmpty() ? ollamaBaseUrl : ollamaUrlParam;

        // 获取客户端 IP 和当前用户 ID
        String clientIp = getClientIp(request);
        Long userId = BaseContext.getCurrentId();

        log.info("========================================");
        log.info("===== ChatController2 stream 请求开始 =====");
        log.info("模型: {}", model);
        ConversationLogger.userMessage("chat", message);
        log.info("是否本地: {}", isLocal);
        log.info("用户消息: {}", message);
        log.info("API地址: {}", apiUrl);
        log.info("历史消息数: {}", messagesJson.isEmpty() ? 0 : countMessages(messagesJson));
        log.info("客户端 IP: {}", clientIp);
        log.info("用户 ID: {}", userId);
        log.info("========================================");

        // 用数组持有 token 统计值（Lambda 内需要可变引用）
        final int[] promptTokensHolder = {0};
        final int[] completionTokensHolder = {0};

        Flux<ServerSentEvent<String>> flux;
        if (isLocal || apiUrl.isEmpty()) {
            flux = handleLocalStream(model, message, messagesJson, requestStart, lang, effectiveOllamaUrl);
        } else {
            flux = handleRemoteStream(model, message, messagesJson, lang, requestStart);
        }

        // 拦截 stats 事件提取 token 数
        flux = flux.doOnNext(event -> {
            if ("stats".equals(event.event()) && event.data() != null) {
                try {
                    JsonNode statsNode = JSON.readTree(event.data());
                    promptTokensHolder[0] = statsNode.path("promptTokens").asInt(0);
                    completionTokensHolder[0] = statsNode.path("completionTokens").asInt(0);
                } catch (Exception ignored) {}
            }
        });

        // 客户端断开连接时（如按 Esc/关闭页面），优雅完成流，不抛异常
        flux = flux.onErrorResume(IOException.class, e -> {
            log.info("===== SSE 流写入中断（客户端已断开）: {} =====", e.getMessage());
            return Flux.empty();
        });

        // 流结束后异步记录 token 统计
        return flux.doFinally(signalType -> {
            int promptTokens = promptTokensHolder[0];
            int completionTokens = completionTokensHolder[0];
            long duration = System.currentTimeMillis() - requestStart;
            int status = signalType == SignalType.ON_COMPLETE ? 1 : 0;
            tokenUsageService.recordAsync(userId, clientIp, model,
                    promptTokens, completionTokens, duration, status);
        });
    }

    /** 粗略计算 JSON 中的消息数 */
    private int countMessages(String json) {
        try {
            return JSON.readTree(json).size();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 处理本地 Ollama 流式对话
     *
     * <p>通过 {@link HttpURLConnection} 直接调用 Ollama {@code /api/chat} 接口，
     * 使用 {@code Flux.create()} + {@code Schedulers.boundedElastic()} 将阻塞 IO 桥接到响应式链路。</p>
     *
     * <p>Ollama 响应格式（每行一个 JSON）：</p>
     * <pre>
     * {"message":{"role":"assistant","content":"","thinking":"..."},"done":false}
     * {"message":{"role":"assistant","content":"你好","thinking":""},"done":false}
     * {"done":true,"done_reason":"stop"}
     * </pre>
     *
     * @param model        模型名称
     * @param message      用户消息
     * @param requestStart 请求开始时间戳（用于统计耗时）
     * @return SSE 事件流
     */
    private static final int MAX_HISTORY_MESSAGES = 20; // 最多保留最近 10 轮对话

    private List<Map<String, String>> buildMessages(String message, String messagesJson, String lang) {
        List<Map<String, String>> result = new ArrayList<>();
        // 系统提示词（根据语言参数选择）
        String prompt = SYSTEM_PROMPTS.getOrDefault(lang, SYSTEM_PROMPTS.get("zh-CN"));
        result.add(Map.of("role", "system", "content", prompt));
        // 历史消息（由前端传入的 JSON 数组，截断最近 MAX_HISTORY_MESSAGES 条）
        if (messagesJson != null && !messagesJson.isEmpty()) {
            try {
                JsonNode arr = JSON.readTree(messagesJson);
                if (arr.isArray()) {
                    int total = arr.size();
                    int start = Math.max(0, total - MAX_HISTORY_MESSAGES);
                    for (int i = start; i < total; i++) {
                        JsonNode node = arr.get(i);
                        String role = node.path("role").asText();
                        String content = node.path("content").asText();
                        if (!role.isEmpty()) {
                            result.add(Map.of("role", role, "content", content));
                        }
                    }
                    // 添加当前用户消息
                    result.add(Map.of("role", "user", "content", message));
                    return result;
                }
            } catch (Exception e) {
                log.warn("解析历史消息失败: {}", e.getMessage());
            }
        }
        // 无历史消息时仅保留用户当前输入
        result.add(Map.of("role", "user", "content", message));
        return result;
    }

    private Flux<ServerSentEvent<String>> handleLocalStream(String model, String message, String messagesJson, long requestStart, String lang, String ollamaUrl) {
        long streamStart = System.currentTimeMillis();
        AtomicInteger chunkCount = new AtomicInteger(0);
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullReasoning = new StringBuilder();

        String baseUrl = ollamaUrl.replaceAll("/$", "");

        return Flux.<String>create(emitter -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(baseUrl + "/api/chat");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(0);
                conn.setUseCaches(false);

                // 构造 Ollama 请求体（含系统提示词和历史消息，优化参数加速响应）
                List<Map<String, String>> messagesList = buildMessages(message, messagesJson, lang);
                String body = JSON.writeValueAsString(Map.of(
                        "model", model,
                        "messages", messagesList,
                        "stream", true,
                        "options", Map.of(
                                "temperature", 0.7,
                                "num_predict", 2048,      // 限制最大生成 token，防止过长
                                "num_ctx", 4096,           // 上下文窗口
                                "repeat_penalty", 1.1,    // 减少重复循环
                                "repeat_last_n", 256
                        )
                ));
                log.info("===== [Ollama] 发送请求 =====\n模型: {}\n消息: {}\n请求体: {}", model, message, body);
                conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    String err = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                    log.error("Ollama 返回 {}: {}", responseCode, err);
                    emitter.error(new RuntimeException("Ollama 返回 " + responseCode));
                    return;
                }

                // 逐行读取 Ollama 流式响应
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            emitter.next(line);
                        }
                    }
                }
                emitter.complete();
            } catch (IOException e) {
                // 客户端主动断开（Esc/切换页面/关闭标签），正常行为，不报 ERROR
                log.info("===== [Ollama] 客户端断开连接 =====");
                emitter.complete();
            } catch (Exception e) {
                log.error("===== [Ollama] 请求失败: {} =====", e.getMessage());
                emitter.error(e);
            } finally {
                conn.disconnect();
            }
        }, FluxSink.OverflowStrategy.BUFFER)
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(line -> parseOllamaLine(line, model, fullReasoning, fullContent, chunkCount, streamStart, requestStart))
        .doOnCancel(() -> log.info("===== [Ollama] 请求被取消 ====="));
    }

    /**
     * 解析 Ollama 单行响应并转换为 SSE 事件
     *
     * <p>Ollama 的思考模型（如 gemma4）将推理过程放在 {@code thinking} 字段，
     * 正式回复放在 {@code content} 字段。部分模型使用 {@code reasoning_content} 字段。</p>
     *
     * @param line           Ollama 返回的 JSON 行
     * @param model          模型名称（用于日志）
     * @param fullReasoning  累积的推理内容（用于统计）
     * @param fullContent    累积的正式回复（用于统计）
     * @param chunkCount     内容块计数器
     * @param streamStart    流开始时间戳
     * @param requestStart   请求开始时间戳
     * @return 解析后的 SSE 事件流
     */
    private Flux<ServerSentEvent<String>> parseOllamaLine(String line, String model,
                                                           StringBuilder fullReasoning, StringBuilder fullContent,
                                                           AtomicInteger chunkCount, long streamStart, long requestStart) {
        try {
            JsonNode node = JSON.readTree(line);
            JsonNode msg = node.get("message");
            if (msg != null) {
                List<ServerSentEvent<String>> events = new ArrayList<>();

                // 优先读取 reasoning_content（远程 API 格式），回退到 thinking（Ollama 格式）
                JsonNode rc = msg.get("reasoning_content");
                if (rc == null || rc.isNull()) rc = msg.get("thinking");
                if (rc != null && !rc.isNull() && !rc.asText().isEmpty()) {
                    String text = rc.asText();
                    fullReasoning.append(text);
                    // 用 JSON 包装 data，防止 \n 破坏 SSE 协议
                    events.add(ServerSentEvent.<String>builder()
                            .event("reasoning").data(JSON.writeValueAsString(text)).build());
                }

                // 读取正式回复内容（包括换行符 \n，用 JSON 包装保护转义）
                JsonNode ct = msg.get("content");
                if (ct != null && !ct.isNull()) {
                    String text = ct.asText();
                    if (!text.isEmpty()) {
                        fullContent.append(text);
                        chunkCount.incrementAndGet();
                        // 用 JSON 包装 data，防止 \n 破坏 SSE 协议
                        events.add(ServerSentEvent.<String>builder()
                                .event("content").data(JSON.writeValueAsString(text)).build());
                    }
                }
                if (!events.isEmpty()) return Flux.fromIterable(events);
            }

            // 检查流是否结束
            if (node.has("done") && node.get("done").asBoolean()) {
                long elapsed = System.currentTimeMillis() - streamStart;

                // 提取 token 计数
                int promptTokens = node.path("prompt_eval_count").asInt(0);
                int completionTokens = node.path("eval_count").asInt(0);
                long totalDuration = node.path("total_duration").asLong(0);

                log.info("========================================");
                log.info("===== [Ollama] 流式输出完成 =====");
                log.info("模型: {}", model);
                log.info("内容块数: {}", chunkCount.get());
                log.info("reasoning 字符数: {}", fullReasoning.length());
                log.info("content 字符数: {}", fullContent.length());
                log.info("prompt_tokens: {}, completion_tokens: {}", promptTokens, completionTokens);
                log.info("流式耗时: {} ms", elapsed);
                log.info("总耗时: {} ms", System.currentTimeMillis() - requestStart);
                log.info("----- 完整 reasoning -----");
                log.info("{}", fullReasoning);
                log.info("----- 完整 content -----");
                log.info("{}", fullContent);
                log.info("========================================");

                // 发送 stats 事件 + done 事件
                return Flux.just(
                    ServerSentEvent.<String>builder()
                        .event("stats")
                        .data(JSON.writeValueAsString(Map.of(
                            "promptTokens", promptTokens,
                            "completionTokens", completionTokens,
                            "totalDuration", totalDuration
                        ))).build(),
                    DONE_EVENT
                );
            }
        } catch (Exception e) {
            log.warn("解析 Ollama 响应行失败: {}", line);
        }
        return Flux.empty();
    }

    /**
     * 处理远程 OpenAI 兼容 API 流式对话
     *
     * <p>通过 Spring AI {@link ChatClient} 调用 LLMConfig 中注册的 ChatModel Bean，
     * 输出 SSE 格式的流式响应。支持 reasoning_content、token 统计等。</p>
     *
     * @param model        模型名称
     * @param message      用户消息
     * @param messagesJson 历史消息 JSON 数组
     * @param lang         语言参数
     * @param requestStart 请求开始时间戳
     * @return SSE 事件流
     */
    private Flux<ServerSentEvent<String>> handleRemoteStream(String model, String message,
                                 String messagesJson, String lang, long requestStart) {
        long streamStart = System.currentTimeMillis();
        AtomicInteger chunkCount = new AtomicInteger(0);
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullReasoning = new StringBuilder();

        // 从 Spring 容器获取注册的 ChatModel Bean
        ChatModel chatModel;
        try {
            chatModel = applicationContext.getBean(model + "ChatModel", ChatModel.class);
        } catch (Exception e) {
            log.error("OpenAI 模型 '{}' 未在 LLMConfig 中注册: {}", model, e.getMessage());
            return Flux.just(DONE_EVENT);
        }

        List<Map<String, String>> messagesList = buildMessages(message, messagesJson, lang);

        // 用数组持有 token 统计
        final int[] promptTokensHolder = {0};
        final int[] completionTokensHolder = {0};

        // 转换 Map messages 为 Spring AI Message 对象
        List<Message> aiMessages = new ArrayList<>();
        for (Map<String, String> m : messagesList) {
            String role = m.get("role");
            String content = m.get("content");
            if ("system".equals(role)) {
                aiMessages.add(new SystemMessage(content));
            } else if ("user".equals(role)) {
                aiMessages.add(new UserMessage(content));
            } else if ("assistant".equals(role)) {
                aiMessages.add(new AssistantMessage(content));
            }
        }

        return ChatClient.create(chatModel)
                .prompt()
                .messages(aiMessages)
                .stream()
                .chatResponse()
                .flatMap(response -> {
                    List<ServerSentEvent<String>> events = new ArrayList<>();
                    if (response.getResults() != null) {
                        for (var result : response.getResults()) {
                            var output = result.getOutput();
                            if (output != null) {
                                String content = output.getText();
                                if (content != null && !content.isEmpty()) {
                                    fullContent.append(content);
                                    chunkCount.incrementAndGet();
                                    try {
                                        events.add(ServerSentEvent.<String>builder()
                                                .event("content")
                                                .data(JSON.writeValueAsString(content)).build());
                                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                                        // should not happen for simple strings
                                    }
                                }
                            }
                        }
                    }
                    var usage = response.getMetadata().getUsage();
                    if (usage != null) {
                        promptTokensHolder[0] = (int) usage.getPromptTokens();
                        completionTokensHolder[0] = (int) usage.getCompletionTokens();
                    }
                    return events.isEmpty() ? Flux.empty() : Flux.fromIterable(events);
                })
                .concatWithValues(
                    buildStatsEvent(promptTokensHolder[0], completionTokensHolder[0]),
                    DONE_EVENT
                )
                .doOnComplete(() -> {
                    long elapsed = System.currentTimeMillis() - streamStart;
                    log.info("===== [Remote] 流式输出完成 =====");
                    log.info("模型: {}, 内容块数: {}", model, chunkCount.get());
                    log.info("prompt_tokens: {}, completion_tokens: {}", promptTokensHolder[0], completionTokensHolder[0]);
                    log.info("流式耗时: {} ms", elapsed);
                })
                .doOnError(err -> log.error("===== [Remote] 请求失败: {} =====", err.getMessage()));
    }

    /** 构建 token 统计 SSE 事件，捕获 JSON 序列化异常 */
    private ServerSentEvent<String> buildStatsEvent(int promptTokens, int completionTokens) {
        try {
            return ServerSentEvent.<String>builder()
                    .event("stats")
                    .data(JSON.writeValueAsString(Map.of(
                            "promptTokens", promptTokens,
                            "completionTokens", completionTokens
                    ))).build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return ServerSentEvent.<String>builder()
                    .event("stats")
                    .data("{}").build();
        }
    }

    /**
     * 解析远程 API 单行 SSE 数据并转换为事件
     *
     * <p>远程 API 响应格式（OpenAI 兼容）：</p>
     * <pre>
     * {"choices":[{"delta":{"reasoning_content":"思考中..."}}]}
     * {"choices":[{"delta":{"content":"你好"}}]}
     * </pre>
     *
     * @param data         SSE data 字段的 JSON 字符串
     * @param model        模型名称
     * @param fullContent  累积的回复内容
     * @param chunkCount   内容块计数器
     * @return 解析后的 SSE 事件流
     */
    @Deprecated
    private Flux<ServerSentEvent<String>> parseRemoteLine(String data, String model,
                                                           StringBuilder fullContent, AtomicInteger chunkCount) {
        try {
            JsonNode node = JSON.readTree(data);
            JsonNode delta = node.path("choices").get(0).path("delta");
            List<ServerSentEvent<String>> events = new ArrayList<>();

            JsonNode rc = delta.get("reasoning_content");
            if (rc != null && !rc.isNull() && !rc.asText().isEmpty()) {
                events.add(ServerSentEvent.<String>builder()
                        .event("reasoning").data(JSON.writeValueAsString(rc.asText())).build());
            }
            JsonNode ct = delta.get("content");
            if (ct != null && !ct.isNull()) {
                String text = ct.asText();
                if (!text.isEmpty()) {
                    fullContent.append(text);
                    chunkCount.incrementAndGet();
                    events.add(ServerSentEvent.<String>builder()
                            .event("content").data(JSON.writeValueAsString(text)).build());
                }
            }
            return events.isEmpty() ? Flux.empty() : Flux.fromIterable(events);
        } catch (Exception e) {
            log.warn("解析远程响应行失败: {}", data);
            return Flux.empty();
        }
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
    public List<ModelInfo> listModels(
            @RequestParam(value = "apiUrl", defaultValue = "") String apiUrl,
            @RequestParam(value = "apiKey", defaultValue = "") String apiKey,
            @RequestParam(value = "ollamaUrl", defaultValue = "") String ollamaUrlParam) {
        List<ModelInfo> models;
        if (apiUrl != null && !apiUrl.isEmpty()) {
            models = fetchRemoteModels(apiUrl, apiKey);
        } else {
            models = fetchLocalOllamaModels(ollamaUrlParam.isEmpty() ? ollamaBaseUrl : ollamaUrlParam);
        }
        // 追加配置中的 openai 类型云端模型
        try {
            ModelConfigProperties props = applicationContext.getBean(ModelConfigProperties.class);
            if (props != null && props.getModels() != null) {
                for (var mc : props.getModels()) {
                    if ("openai".equalsIgnoreCase(mc.getType())) {
                        models.add(new ModelInfo(mc.getName(), mc.getName(), "cloud", mc.getName()));
                    }
                }
            }
        } catch (Exception ignored) {}
        return models;
    }

    /**
     * 从本地 Ollama 获取模型列表
     *
     * <p>调用 Ollama {@code /api/tags} 端点获取已安装的模型。</p>
     *
     * @return 模型信息列表
     */
    private List<ModelInfo> fetchLocalOllamaModels(String ollamaUrl) {
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
            List<ModelInfo> result = new ArrayList<>();
            for (JsonNode m : root.get("models")) {
                String name = m.get("name").asText();
                String family = m.path("details").path("family").asText("");
                result.add(new ModelInfo(name, name, "ollama", family));
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
    private List<ModelInfo> fetchRemoteModels(String apiUrl, String apiKey) {
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
            List<ModelInfo> result = new ArrayList<>();
            for (Map<String, Object> m : data) {
                String id = (String) m.get("id");
                if (id != null) {
                    result.add(new ModelInfo(id, id, "remote", ""));
                }
            }

            log.info("远程 API 获取到 {} 个模型", result.size());
            return result;
        } catch (Exception e) {
            log.error("获取远程模型列表失败: {}", e.getMessage());
            throw new RuntimeException("无法连接远程 API: " + e.getMessage());
        }
    }

    /**
     * 模型信息记录
     *
     * @param name   模型标识名（用于 API 调用）
     * @param label  模型显示名称
     * @param source 模型来源（"ollama" 或 "remote"）
     * @param family 模型系列（如 "gemma4"、"qwen35moe"）
     */
    record ModelInfo(String name, String label, String source, String family) {}

    /**
     * 从请求头获取客户端真实 IP（优先 X-Forwarded-For）
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : "127.0.0.1";
    }

    /**
     * 消息路由判断：分析用户消息意图，区分普通聊天和研发工作流
     * @since 2026-07-29
     */
    @PostMapping("/route")
    public BaseResponse<Map<String, String>> route(@RequestBody Map<String, String> body) {
        String message = body.get("message");
        boolean isWorkflow = isWorkflowIntent(message);
        return ResultUtils.success(Map.of("type", isWorkflow ? "workflow" : "chat"));
    }

    private boolean isWorkflowIntent(String message) {
        if (message == null || message.trim().length() < 20) return false;
        if (message.matches("^(怎么|如何|为什么|什么是|啥是|怎样|有没有|能不能|可以).*[？?]$") || message.contains("?") || message.contains("？"))
            return false;
        String[] devKeywords = { "开发", "实现", "设计", "构建", "创建", "做一个", "帮我写", "搭建", "编写", "生成", "写一个", "弄一个" };
        for (String kw : devKeywords) { if (message.contains(kw)) return true; }
        String[] projectPatterns = { "系统", "功能", "工具", "项目", "平台", "应用", "服务", "模块", "接口", "API", "网站", "页面", "后端", "前端" };
        for (String pp : projectPatterns) { if (message.contains(pp) && message.length() > 30) return true; }
        return false;
    }
}
