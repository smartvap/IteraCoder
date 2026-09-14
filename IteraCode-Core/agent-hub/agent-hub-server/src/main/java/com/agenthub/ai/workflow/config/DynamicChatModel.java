package com.agenthub.ai.workflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 动态 ChatModel 代理。
 *
 * <p><b>背景（2026-08-13 修改）</b>：工作流图（CompiledGraph）在应用启动时即预构建，
 * 其中的 ChatModel 在构建期就被固定，导致前端切换模型后工作流仍使用默认模型。</p>
 *
 * <p><b>方案</b>：用本代理替代固定 ChatModel，在每次 {@link #call(Prompt)} /
 * {@link #stream(Prompt)} 执行时，读取 {@link RdWorkflowGraphConfig#getRequestModel()}
 * （ThreadLocal，由工作流请求在执行线程内设置），动态解析实际模型：</p>
 * <ol>
 *   <li>优先匹配已注册的 Bean（{@code modelName + "ChatModel"}）</li>
 *   <li>Bean 不存在则动态创建 {@link OllamaChatModel}（直接用前端模型名请求 Ollama）</li>
 *   <li>无运行时模型时回退到配置默认模型</li>
 *   <li><b>2026-08-24</b>：请求的模型在 Ollama 上不存在时，自动通过 {@code /api/tags} 获取
 *       实际可用模型列表，回退到第一个可用模型，规避 404 model not found</li>
 * </ol>
 *
 * <p><b>注意</b>：Spring AI 1.1.2 中 {@link ChatModel} 与 {@link StreamingChatModel}
 * 为平级接口，故本类需同时实现两者；且 Java 17 下不可使用 {@code instanceof} 模式匹配
 * （无条件模式），需采用旧式 instanceof + 强制类型转换。</p>
 *
 * @author AgentHub
 * @since 2026-08-13
 */
public class DynamicChatModel implements ChatModel, StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(DynamicChatModel.class);

    /** 已注册的 ChatModel Bean 注册表（beanName → ChatModel） */
    private final Map<String, ChatModel> modelRegistry;

    /** 配置的默认模型名（如 qwen3:8b） */
    private final String defaultModelName;

    /** Ollama API 实例（复用已配置的，自带 readTimeout） */
    private final OllamaApi ollamaApi;

    /** 动态创建的模型缓存，避免重复创建 */
    private final ConcurrentHashMap<String, ChatModel> dynamicCache = new ConcurrentHashMap<>();

    /** Ollama 上实际可用的模型名列表缓存（避免频繁请求 /api/tags） */
    private volatile List<String> availableModelNamesCache = null;

    /** 缓存失效时间（毫秒） */
    private volatile long cacheExpireAt = 0;

    /** 模型列表缓存时长：60 秒 */
    private static final long CACHE_TTL_MS = 60_000L;

    public DynamicChatModel(Map<String, ChatModel> modelRegistry, String defaultModelName, OllamaApi ollamaApi) {
        this.modelRegistry = modelRegistry;
        this.defaultModelName = defaultModelName;
        this.ollamaApi = ollamaApi;
    }

    /**
     * 解析实际模型。
     * <p>优先级：运行时 ThreadLocal 模型 → 配置默认模型 → 第一个可用 Bean。</p>
     */
    private ChatModel resolveModel() {
        String requested = RdWorkflowGraphConfig.getRequestModel();
        String modelName;
        if (requested != null && !requested.isBlank()) {
            ChatModel m = modelRegistry.get(requested + "ChatModel");
            if (m != null) return m;
            modelName = requested;
        } else {
            ChatModel m = modelRegistry.get(defaultModelName + "ChatModel");
            if (m != null) return m;
            modelName = defaultModelName;
        }
        // 模型名不存在时，自动回退到 Ollama 实际可用的模型，规避 404
        String resolved = ensureModelExists(modelName);
        final String finalName = resolved;
        return dynamicCache.computeIfAbsent(finalName, name -> {
            log.info("[DynamicChatModel] 动态创建 OllamaChatModel: model={} (原请求: {})", name, modelName);
            OllamaChatOptions options = OllamaChatOptions.builder().model(name).build();
            return OllamaChatModel.builder().ollamaApi(ollamaApi).defaultOptions(options).build();
        });
    }

    /**
     * 确保模型名在 Ollama 上存在。
     * <p>若请求的模型不存在，则通过 {@code /api/tags} 获取实际可用模型列表，
     * 返回第一个可用模型；无法获取列表时保持原模型名（交由 Ollama 返回明确错误）。</p>
     *
     * @param requestedModel 请求的模型名
     * @return 实际应使用的模型名
     */
    private String ensureModelExists(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return requestedModel;
        }
        List<String> names = getAvailableModelNames();
        if (names.isEmpty()) {
            // 无法获取列表（Ollama 不可达等），保持原模型名
            return requestedModel;
        }
        // 精确匹配（含 tag）
        if (names.contains(requestedModel)) {
            return requestedModel;
        }
        // 前缀匹配：如请求 gemma4，实际有 gemma4:26b
        String prefix = requestedModel.split(":")[0];
        for (String n : names) {
            if (n.equals(prefix) || n.startsWith(prefix + ":")) {
                log.warn("[DynamicChatModel] 请求模型 '{}' 不存在，回退到可用模型 '{}'", requestedModel, n);
                return n;
            }
        }
        // 回退到第一个可用模型
        String first = names.get(0);
        log.warn("[DynamicChatModel] 请求模型 '{}' 不存在于 Ollama，回退到第一个可用模型 '{}'", requestedModel, first);
        return first;
    }

    /**
     * 获取 Ollama 实际可用的模型名列表（带 60 秒缓存）。
     * 通过 {@code GET {baseUrl}/api/tags} 获取。
     */
    private List<String> getAvailableModelNames() {
        long now = System.currentTimeMillis();
        if (availableModelNamesCache != null && now < cacheExpireAt) {
            return availableModelNamesCache;
        }
        try {
            OllamaApi.ListModelResponse resp = ollamaApi.listModels();
            if (resp != null && resp.models() != null && !resp.models().isEmpty()) {
                List<String> names = resp.models().stream()
                        .map(OllamaApi.Model::name)
                        .distinct()
                        .collect(java.util.stream.Collectors.toCollection(CopyOnWriteArrayList::new));
                availableModelNamesCache = names;
                cacheExpireAt = now + CACHE_TTL_MS;
                log.info("[DynamicChatModel] 获取到 Ollama 可用模型 {} 个: {}", names.size(), names);
                return names;
            }
            log.warn("[DynamicChatModel] Ollama /api/tags 返回空列表");
        } catch (Exception e) {
            log.warn("[DynamicChatModel] 获取 Ollama 模型列表失败: {}", e.getMessage());
        }
        return List.of();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return resolveModel().call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ChatModel m = resolveModel();
        if (m instanceof StreamingChatModel) {
            StreamingChatModel streaming = (StreamingChatModel) m;
            return streaming.stream(prompt);
        }
        // 非流式模型回退为一次性调用
        return Flux.just(m.call(prompt));
    }
}
