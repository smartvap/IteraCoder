package com.agenthub.ai.rcagent.feedback.adapter;

import com.agenthub.ai.llmwiki.client.LlmWikiClient;
import com.agenthub.ai.llmwiki.client.LlmWikiClientException;
import com.agenthub.ai.llmwiki.dto.PageVO;
import com.agenthub.ai.llmwiki.dto.PageWriteRequest;
import com.agenthub.ai.llmwiki.dto.SyncTaskVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 存量 llm-wiki（知识库 B）→ feedback-loop 写回适配器（brownfield 边界）。
 *
 * <p>复用存量 {@link LlmWikiClient} createPage/updatePage/syncPages/getSyncTask
 * （JDK HttpClient HTTP/1.1 风格由 LlmWikiClient 封装），FAQ/概念/指标口径沉淀页写回库 B。
 * 远端不可用时抛 LlmWikiClientException 由写回服务转 PENDING/FAILED 重试，
 * 不阻塞库 A 与闭环（NFR-BE-003）。</p>
 */
@Slf4j
@Component
public class LlmWikiWritebackAdapter {

    private final LlmWikiClient client;

    public LlmWikiWritebackAdapter(LlmWikiClient client) {
        this.client = client;
    }

    /**
     * 写页面：页面已存在则 updatePage，否则 createPage（幂等写回）。
     * path 含中文/子目录由 LlmWikiClient 按段 URL 编码。
     *
     * @return PageVO
     */
    public PageVO writePage(String space, String path, String content) {
        PageWriteRequest req = new PageWriteRequest(path, content);
        PageVO existing = null;
        try {
            existing = client.getPage(space, path);
        } catch (LlmWikiClientException e) {
            // 404/远端不可用：置空按新建尝试；真实远端错误由下方 create/update 捕获
            if (e.getStatusCode() >= 500 || e.getStatusCode() <= 0) {
                throw e;
            }
        }
        if (existing != null && existing.path() != null) {
            log.info("feedback: llm-wiki 页面已存在走更新 space={} path={}", space, path);
            return client.updatePage(space, path, req);
        }
        log.info("feedback: llm-wiki 新建页面 space={} path={}", space, path);
        return client.createPage(space, req);
    }

    /** 发起异步增量同步（空间级），返回远端任务 */
    public SyncTaskVO syncPages(String space) {
        return client.syncPages(space);
    }

    /** 轮询远端同步任务状态 */
    public SyncTaskVO getSyncTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return client.getSyncTask(taskId);
    }

    /** 远端任务是否成功终态（completed） */
    public static boolean isTaskCompleted(SyncTaskVO task) {
        return task != null && "completed".equalsIgnoreCase(task.status());
    }

    /** 远端任务是否失败终态 */
    public static boolean isTaskFailed(SyncTaskVO task) {
        return task != null && ("error".equalsIgnoreCase(task.status())
                || (task.error() != null && !task.error().isBlank()));
    }
}
