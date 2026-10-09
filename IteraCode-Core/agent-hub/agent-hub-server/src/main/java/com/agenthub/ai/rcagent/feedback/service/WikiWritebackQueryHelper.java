package com.agenthub.ai.rcagent.feedback.service;

import com.agenthub.ai.rcagent.feedback.entity.WikiWritebackTask;
import com.agenthub.ai.rcagent.feedback.mapper.WikiWritebackTaskMapper;
import org.springframework.stereotype.Component;

/**
 * fb_wiki_writeback 查询小助手（供 FeedbackQueryService 组详情 B 库进度）。
 */
@Component
public class WikiWritebackQueryHelper {

    private final WikiWritebackTaskMapper wikiTaskMapper;

    public WikiWritebackQueryHelper(WikiWritebackTaskMapper wikiTaskMapper) {
        this.wikiTaskMapper = wikiTaskMapper;
    }

    /** 最新一条 B 库出站状态（无任务返回 null） */
    public String latestStatus(String dsId, Long feedbackId) {
        WikiWritebackTask task = wikiTaskMapper.selectLatestByFeedback(dsId, feedbackId);
        return task == null ? null : task.getStatus();
    }
}
