package com.agenthub.ai.workflow.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工作流事件总线：线程安全，各节点推送事件 → SSE 端点消费。
 * <p>
 * 使用方式：
 * <pre>
 *   eventBus.publish(threadId, key, value, status);
 *   // SSE 端: eventBus.subscribe(threadId) → BlockingQueue
 * </pre>
 */
@Slf4j
@Component
public class WorkflowEventBus {

    private final Map<String, BlockingQueue<Event>> channels = new ConcurrentHashMap<>();
    /** 每个 threadId 的 epoch（代），resume 时递增，用于过滤旧代事件 */
    private final Map<String, AtomicInteger> epochs = new ConcurrentHashMap<>();

    /**
     * 工作流执行事件。
     *
     * @param key     State 键名（如 decomposition_result、generated_code）
     * @param value   事件值（全量文本 或 增量片段）
     * @param status  工作流状态（RUNNING / WAITING_REVIEW / COMPLETED）
     * @param epoch   事件所属的代（epoch），用于过滤旧代残留事件
     * @param isDelta true=增量片段（前端拼接），false=全量文本（前端覆盖）
     */
    public record Event(String key, String value, String status, long timestamp, int epoch, boolean isDelta) {
        public Event(String key, String value, String status, long timestamp, int epoch) {
            this(key, value, status, timestamp, epoch, false);
        }
    }

    /**
     * 获取当前 epoch（供 SSE 端点判断事件是否属于当前代）。
     */
    public int getEpoch(String threadId) {
        return epochs.computeIfAbsent(threadId, k -> new AtomicInteger(0)).get();
    }

    /**
     * 递增 epoch（resume 时调用），使旧代事件全部失效。
     */
    public int incrementEpoch(String threadId) {
        int newEpoch = epochs.computeIfAbsent(threadId, k -> new AtomicInteger(0)).incrementAndGet();
        log.debug("epoch 递增: threadId={}, newEpoch={}", threadId, newEpoch);
        return newEpoch;
    }

    /**
     * 发布全量事件到指定线程通道。
     */
    public void publish(String threadId, String key, String value, String status) {
        publish(threadId, key, value, status, false);
    }

    /**
     * 发布增量事件：value 为增量片段，前端自行拼接。
     */
    public void publishDelta(String threadId, String key, String value, String status) {
        publish(threadId, key, value, status, true);
    }

    private void publish(String threadId, String key, String value, String status, boolean isDelta) {
        BlockingQueue<Event> queue = channels.computeIfAbsent(threadId,
                k -> new LinkedBlockingQueue<>(8192));
        int epoch = epochs.computeIfAbsent(threadId, k -> new AtomicInteger(0)).get();
        Event event = new Event(key, value, status, System.currentTimeMillis(), epoch, isDelta);
        // offer 失败说明队列满，丢弃最旧事件后重试
        if (!queue.offer(event)) {
            queue.poll();
            queue.offer(event);
        }
    }

    /**
     * 订阅指定线程的事件流（阻塞等待，供 SSE 使用）。
     *
     * @return 事件，或 null（超时/中断）
     */
    public Event subscribe(String threadId, long timeoutMs) {
        BlockingQueue<Event> queue = channels.computeIfAbsent(threadId,
                k -> new LinkedBlockingQueue<>(4096));
        try {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 清理指定线程的通道（工作流结束后调用）。
     */
    public void removeChannel(String threadId) {
        channels.remove(threadId);
    }

    /**
     * 清空指定线程队列中的所有待消费事件，并递增 epoch 使旧代事件全部失效。
     * <p>
     * resume 时调用，避免旧 pushFinalState 线程的残留事件污染新 SSE 连接。
     * 即使旧线程在 clearEvents 后继续 publish，新 SSE 连接也能通过 epoch 过滤掉。
     */
    public void clearEvents(String threadId) {
        BlockingQueue<Event> queue = channels.get(threadId);
        if (queue != null) {
            queue.clear();
        }
        // 递增 epoch，使旧线程后续 publish 的事件 epoch 不等于新 epoch，被 SSE 端过滤
        incrementEpoch(threadId);
        log.debug("通道已清空并递增 epoch: threadId={}", threadId);
    }
}
