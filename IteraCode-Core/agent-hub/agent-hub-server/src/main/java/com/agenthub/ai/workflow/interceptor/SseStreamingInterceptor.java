package com.agenthub.ai.workflow.interceptor;

import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.node.MultiRoundAgentNode;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.StreamingModelInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 流式拦截器：将 ReactAgent 内部的流式 LLM 输出转发到 WorkflowEventBus，
 * 使前端能实时看到沙箱验证、代码修复等步骤的推理过程。
 * <p>
 * 工作原理：
 * 1. ReactAgent 内部使用 ChatClient.stream() 进行流式调用
 * 2. 每个 token chunk 到达时触发 onStreamChunk 回调
 * 3. 拦截器将增量内容累积后通过 eventBus.publish() 推送到 SSE 通道
 * 4. 前端 SSE 端点消费事件，实时渲染到对应步骤
 * <p>
 * threadId 获取策略：
 * ReactAgent 的工具调用循环可能跨多个线程（bounded elastic），
 * 因此不能用 ThreadLocal。改为通过 WorkflowEventBus 的全局 epoch 注册表
 * 反向查找当前活跃的 threadId。
 * 但更可靠的方式是：在 ModelRequest.getContext() 中查找，
 * 如果找不到则从 ModelRequest 的其他字段推断。
 * <p>
 * 最终方案：使用一个静态注册表，RdWorkflowService 在启动工作流时注册 threadId，
 * interceptor 从中读取。由于一个 JVM 中同一时刻通常只有少量活跃工作流，
 * 注册表开销可忽略。
 */
@Slf4j
public class SseStreamingInterceptor implements StreamingModelInterceptor {

    private final WorkflowEventBus eventBus;
    private final String outputKey;

    /**
     * 全局 threadId 注册表：key = outputKey，value = 当前活跃的 threadId。
     * RdWorkflowService.start()/resume() 时注册，工作流结束时清除。
     * 使用 outputKey 作为关联键（每个 Agent 节点有唯一的 outputKey）。
     */
    private static final Map<String, String> activeThreadIds = new ConcurrentHashMap<>();

    /**
     * 注册当前工作流的 threadId，使拦截器能获取到。
     *
     * @param outputKey Agent 的输出 key（如 harness_result、generated_code）
     * @param threadId  工作流线程 ID
     */
    public static void registerThreadId(String outputKey, String threadId) {
        activeThreadIds.put(outputKey, threadId);
    }

    /**
     * 获取当前活跃的 threadId（供 CodeRepairTools 等工具类使用）。
     *
     * @param outputKey Agent 的输出 key（如 generated_code）
     * @return threadId，未注册则返回 null
     */
    public static String getActiveThreadId(String outputKey) {
        return activeThreadIds.get(outputKey);
    }

    /**
     * 注销 threadId（工作流结束时调用）。
     */
    public static void unregisterThreadId(String outputKey) {
        activeThreadIds.remove(outputKey);
    }

    /**
     * 每次工具调用循环的累积 buffer。
     * ReactAgent 在工具调用循环中可能多次调用 LLM（每次工具返回后继续推理），
     * 每次调用需要独立累积，但最终推送到同一个 SSE 步骤。
     */
    private final Map<Integer, StringBuilder> buffers = new ConcurrentHashMap<>();

    /**
     * SSE 推送行数上限：超过此行数后，增量 delta 不再推送到前端（避免横幅过长）。
     * 完整内容仍会通过 afterStreamComplete 的全量推送（extractSummary）截断后覆盖。
     */
    private final int maxLines;

    /**
     * SSE 推送字符上限：超过此字符数后停止 delta 推送。
     */
    private final int maxChars;

    public SseStreamingInterceptor(WorkflowEventBus eventBus, String outputKey) {
        this(eventBus, outputKey, 5, 800);
    }

    public SseStreamingInterceptor(WorkflowEventBus eventBus, String outputKey, int maxLines, int maxChars) {
        this.eventBus = eventBus;
        this.outputKey = outputKey;
        this.maxLines = maxLines;
        this.maxChars = maxChars;
    }

    @Override
    public ModelRequest beforeStreamCall(ModelRequest request) {
        // 为本次 LLM 调用创建新的 buffer
        int requestHash = System.identityHashCode(request);
        buffers.put(requestHash, new StringBuilder());
        return request;
    }

    @Override
    public ChatResponse onStreamChunk(ChatResponse chunk, ModelRequest request) {
        String threadId = resolveThreadId(request);
        if (threadId == null || eventBus == null) {
            return chunk;
        }

        if (chunk.getResult() != null && chunk.getResult().getOutput() != null) {
            String text = chunk.getResult().getOutput().getText();
            if (text != null && !text.isEmpty()) {
                int requestHash = System.identityHashCode(request);
                StringBuilder buffer = buffers.computeIfAbsent(requestHash, k -> new StringBuilder());
                buffer.append(text);

                // 跳过纯空白 chunk（换行、空格等）：不推送到前端，避免无效流量
                // buffer 中仍累积完整内容，供 afterStreamComplete 使用 extractSummary 处理
                if (text.isBlank()) {
                    return chunk;
                }

                // 行数/字符数未超限才推送 delta 到前端
                String accumulated = buffer.toString();
                int lineCount = countLines(accumulated);
                if (lineCount <= maxLines && accumulated.length() <= maxChars) {
                    eventBus.publishDelta(threadId, outputKey, text, "RUNNING");
                }
            }
        }
        return chunk;
    }

    @Override
    public void afterStreamComplete(AssistantMessage result, ModelRequest request) {
        String threadId = resolveThreadId(request);
        if (threadId == null || eventBus == null) {
            buffers.remove(System.identityHashCode(request));
            return;
        }

        int requestHash = System.identityHashCode(request);
        StringBuilder buffer = buffers.remove(requestHash);

        // 推送本轮完整输出（截断后），State 中仍保留完整内容
        String fullContent = buffer != null ? buffer.toString() : "";
        if (result != null && result.getText() != null && !result.getText().isEmpty()) {
            fullContent = result.getText();
        }
        if (!fullContent.isEmpty()) {
            // 代码生成/修复输出跳过全量推送：流式 delta 已逐块推送，afterStreamComplete
            // 的全量覆盖会导致前端内容闪烁（先清空再显示），体验极差。
            if (RdWorkflowKeys.GENERATED_CODE.equals(outputKey)) {
                log.debug("SseStreamingInterceptor [{}] 跳过全量推送（流式 delta 已覆盖），{} 字符",
                        outputKey, fullContent.length());
                return;
            }
            String display = extractSummary(fullContent);
            eventBus.publish(threadId, outputKey, display, "RUNNING");
            log.debug("SseStreamingInterceptor [{}] 本轮流式完成，推送摘要 {} 字符（原始 {} 字符）",
                    outputKey, display.length(), fullContent.length());
        }
    }

    @Override
    public void onStreamError(Throwable error, ModelRequest request) {
        log.error("SseStreamingInterceptor [{}] 流式调用出错: {}", outputKey, error.getMessage(), error);
        buffers.remove(System.identityHashCode(request));
    }

    /**
     * 解析当前工作流的 threadId。
     * 优先从全局注册表获取（由 RdWorkflowService 注册），回退到 ModelRequest context。
     */
    private String resolveThreadId(ModelRequest request) {
        // 优先从全局注册表获取
        String registered = activeThreadIds.get(outputKey);
        if (registered != null) {
            return registered;
        }

        // 回退：尝试从 context 获取
        Map<String, Object> context = request.getContext();
        if (context != null) {
            Object threadIdObj = context.get(MultiRoundAgentNode.THREAD_ID_KEY);
            if (threadIdObj != null) {
                return threadIdObj.toString();
            }
        }

        log.debug("SseStreamingInterceptor [{}] 无法解析 threadId", outputKey);
        return null;
    }

    // ---------- SSE 摘要提取辅助 ----------

    /** 统计字符串行数 */
    private static int countLines(String s) {
        if (s == null || s.isEmpty()) return 0;
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        return count;
    }

    /**
     * 从完整内容中提取适合 SSE 前端展示的摘要。
     * <p>
     * 策略：
     * 1. 如果内容不长（≤ SSE_MAX_LINES 行且 ≤ SSE_MAX_CHARS 字符），原样返回
     * 2. 否则智能提取关键行：
     *    - [ERROR] 行（编译错误、测试失败的核心信息）
     *    - BUILD FAILURE / BUILD SUCCESS
     *    - Tests run: ...（测试统计）
     *    - COMPILE_SUCCESS / TEST_SUCCESS 等关键标记
     *    - Markdown 标题行（## Step 1 等，保留报告结构）
     *    - 表格行（| ... |）
     *    最多保留 SSE_MAX_LINES 行，超出部分追加 "...共 N 条错误，完整详情已传给修复 Agent"
     * <p>
     * 注意：这只是 SSE 推送到前端的展示版本，State 中的完整内容不受影响。
     */
    private String extractSummary(String content) {
        if (content == null || content.isEmpty()) return "";
        int lineCount = countLines(content);
        if (lineCount <= maxLines && content.length() <= maxChars) {
            return content;
        }

        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        int extractedLines = 0;
        int totalErrors = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            boolean isKeyLine = false;

            // 编译/测试错误行
            if (trimmed.contains("[ERROR]") || trimmed.contains("COMPILATION ERROR")) {
                isKeyLine = true;
                totalErrors++;
            }
            // 构建结果
            else if (trimmed.contains("BUILD FAILURE") || trimmed.contains("BUILD SUCCESS")) {
                isKeyLine = true;
            }
            // 测试统计
            else if (trimmed.startsWith("Tests run:") || trimmed.contains("Tests run:")) {
                isKeyLine = true;
            }
            // 关键标记（COMPILE_SUCCESS: true/false 等）
            else if (trimmed.matches("^(COMPILE|TEST|RUNTIME)_(SUCCESS|FAILED|ERROR).*")) {
                isKeyLine = true;
            }
            // Markdown 标题（保留报告骨架）
            else if (trimmed.startsWith("#") || trimmed.startsWith("##") || trimmed.startsWith("###")) {
                isKeyLine = true;
            }
            // 表格行（| ... |）
            else if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                isKeyLine = true;
            }
            // cannot find symbol / package does not exist 等常见编译错误关键字
            else if (trimmed.contains("cannot find symbol") || trimmed.contains("does not exist")
                    || trimmed.contains("incompatible types") || trimmed.contains("has already been defined")
                    || trimmed.contains("is not abstract") || trimmed.contains("does not override")) {
                isKeyLine = true;
                totalErrors++;
            }
            // AssertionError / NullPointerException 等运行时错误关键字
            else if (trimmed.contains("AssertionError") || trimmed.contains("NullPointerException")
                    || trimmed.contains("ClassCastException") || trimmed.contains("Exception")) {
                isKeyLine = true;
            }

            if (isKeyLine) {
                // 关键标记（COMPILE_SUCCESS/RUNTIME_SUCCESS/TEST_SUCCESS）和错误行强制保留，
                // 不设 maxLines 限制，确保末尾的"### 关键标记"不被前面的日志占满丢失。
                boolean isCritical = trimmed.matches("^(COMPILE|TEST|RUNTIME)_(SUCCESS|FAILED|ERROR).*")
                        || trimmed.contains("[ERROR]")
                        || trimmed.contains("Caused by:")
                        || trimmed.contains("BUILD FAILURE");
                if (isCritical) {
                    // 防止无限增长：超过 2 倍 maxChars 才截断
                    if (sb.length() + trimmed.length() + 1 > maxChars * 2) break;
                    sb.append(line).append("\n");
                    extractedLines++;
                } else {
                    if (extractedLines >= maxLines) continue;
                    if (sb.length() + trimmed.length() + 1 > maxChars) break;
                    sb.append(line).append("\n");
                    extractedLines++;
                }
            }
        }

        // 如果没提取到任何关键行，说明内容中没有错误标记
        // 不回退到截断前N行（那全是噪音），返回简洁提示
        if (extractedLines == 0) {
            return "（未检测到关键错误标记，完整详情已传给修复 Agent）";
        }

        // 如果有更多错误未展示，追加提示
        if (totalErrors > extractedLines || lineCount > extractedLines) {
            sb.append("\n... 共 ").append(totalErrors).append(" 条错误")
              .append("，完整详情已传给修复 Agent");
        }

        return sb.toString();
    }

    /** 简单截断：按行/字符截取，用于代码输出等非错误日志场景 */
    private String truncateSimple(String content) {
        if (content == null || content.isEmpty()) return "";
        String[] lines = content.split("\n", maxLines + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < maxLines; i++) {
            if (sb.length() + lines[i].length() + 1 > maxChars) break;
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    /** 截断回退：按行截取前 maxLines 行 */
    private String truncateFallback(String content) {
        String[] lines = content.split("\n", maxLines + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < maxLines; i++) {
            if (sb.length() + lines[i].length() + 1 > maxChars) break;
            sb.append(lines[i]).append("\n");
        }
        sb.append("\n... (内容过长，已截断。完整内容已传给修复 Agent)");
        return sb.toString();
    }
}
