package com.agenthub.ai.rcagent.kbcrawler.collector;

import com.agenthub.ai.rcagent.kbcrawler.dto.RuntimeSqlEventDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 运行时事件内存缓冲（FR-004/NFR-001）：接入方拦截器/字典辅助产生的上报事件先入内存队列，
 * 由 {@link #drain()} 批量取出交给采集服务归一化聚类（达到条数/时间阈值 flush）。
 *
 * <p>安全约束（BR-001）：本缓冲仅内存流转，不落库、不持久化明文参数；服务重启丢失高频模板窗口 MVP 可接受。</p>
 */
@Component
public class RuntimeEventBuffer {

    /** 容量上限（防无界堆积；达到上限时丢弃最旧事件并计数） */
    private static final int CAPACITY = 5000;

    private final BlockingQueue<RuntimeSqlEventDTO> queue = new LinkedBlockingQueue<>(CAPACITY);

    private volatile long droppedCount = 0;

    /** 入队（非阻塞），失败丢弃计数 */
    public void offer(RuntimeSqlEventDTO event) {
        if (event == null) {
            return;
        }
        if (!queue.offer(event)) {
            droppedCount++;
        }
    }

    /** 批量取（最多 max 条） */
    public List<RuntimeSqlEventDTO> drain(int max) {
        List<RuntimeSqlEventDTO> batch = new ArrayList<>(Math.min(max, queue.size()));
        queue.drainTo(batch, max);
        return batch;
    }

    /** 等待一段时间的批量取（供 flush 周期使用） */
    public List<RuntimeSqlEventDTO> pollBatch(int max, long timeoutMs) {
        List<RuntimeSqlEventDTO> batch = new ArrayList<>();
        try {
            RuntimeSqlEventDTO first = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            if (first != null) {
                batch.add(first);
                queue.drainTo(batch, max - 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return batch;
    }

    public int size() {
        return queue.size();
    }

    public long droppedCount() {
        return droppedCount;
    }
}
