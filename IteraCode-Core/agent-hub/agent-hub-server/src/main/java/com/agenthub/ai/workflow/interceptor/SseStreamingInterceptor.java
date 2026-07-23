package com.agenthub.ai.workflow.interceptor;

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
     * 每次工具调用循环的累积 buffer。
     * ReactAgent 在工具调用循环中可能多次调用 LLM（每次工具返回后继续推理），
     * 每次调用需要独立累积，但最终推送到同一个 SSE 步骤。
     */
    private final Map<Integer, StringBuilder> buffers = new ConcurrentHashMap<>();

    public SseStreamingInterceptor(WorkflowEventBus eventBus, String outputKey) {
        this.eventBus = eventBus;
        this.outputKey = outputKey;
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
                eventBus.publishDelta(threadId, outputKey, text, "RUNNING");
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

        // 推送本轮完整输出
        String fullContent = buffer != null ? buffer.toString() : "";
        if (result != null && result.getText() != null && !result.getText().isEmpty()) {
            fullContent = result.getText();
        }
        if (!fullContent.isEmpty()) {
            eventBus.publish(threadId, outputKey, fullContent, "RUNNING");
            log.debug("SseStreamingInterceptor [{}] 本轮流式完成，推送 {} 字符", outputKey, fullContent.length());
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
}
