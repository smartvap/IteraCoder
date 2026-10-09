package com.agenthub.ai.rcagent.feedback.service;

import com.agenthub.ai.rcagent.feedback.adapter.LlmWikiWritebackAdapter;
import com.agenthub.ai.rcagent.feedback.adapter.ReviewTaskAdapter;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackAnalyzeStatus;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackStatus;
import com.agenthub.ai.rcagent.feedback.entity.FeedbackFlow;
import com.agenthub.ai.rcagent.feedback.entity.WikiWritebackTask;
import com.agenthub.ai.rcagent.feedback.mapper.FeedbackFlowMapper;
import com.agenthub.ai.rcagent.feedback.mapper.WikiWritebackTaskMapper;
import com.agenthub.ai.rcagent.feedback.util.FeedbackJsonUtil;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.llmwiki.dto.SyncTaskVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 反馈闭环调度器：
 * <ol>
 *   <li>轮询 module-004 审核任务：fb_feedback_flow 中 status=REVIEWING 且 review_task_ids
 *       全部 approved（无 rejected/pending）→ 置 flow APPROVED 并触发 writeback；全部
 *       rejected → 置 REJECTED（不写知识）；部分 approved + 部分 rejected（无 pending）
 *       → 置 REJECTED 转人工（禁止把未批准建议写入知识）；仍有 pending 则等待下一轮；</li>
 *   <li>fb_wiki_writeback 重试扫描：PENDING/FAILED 到点任务重新出站（写页+sync）；</li>
 *   <li>RUNNING + remote_task_id 任务：轮询远端 llm-wiki 任务终态（completed → SUCCESS，
 *       error → FAILED）。</li>
 * </ol>
 * 不阻塞主流程；模块关闭/重启可继续扫描（幂等）。
 */
@Slf4j
@Component
public class FeedbackReviewScheduler {

    private static final int SCAN_LIMIT = 100;

    private final FeedbackFlowMapper flowMapper;
    private final WikiWritebackTaskMapper wikiTaskMapper;
    private final ReviewTaskAdapter reviewTaskAdapter;
    private final LlmWikiWritebackAdapter wikiWritebackAdapter;
    private final FeedbackWritebackService writebackService;

    public FeedbackReviewScheduler(FeedbackFlowMapper flowMapper,
                                   WikiWritebackTaskMapper wikiTaskMapper,
                                   ReviewTaskAdapter reviewTaskAdapter,
                                   LlmWikiWritebackAdapter wikiWritebackAdapter,
                                   FeedbackWritebackService writebackService) {
        this.flowMapper = flowMapper;
        this.wikiTaskMapper = wikiTaskMapper;
        this.reviewTaskAdapter = reviewTaskAdapter;
        this.wikiWritebackAdapter = wikiWritebackAdapter;
        this.writebackService = writebackService;
    }

    /** 周期：轮询审核通过任务驱动回写（默认 30s） */
    @Scheduled(fixedDelayString = "${agenthub.feedback.scheduler.interval-ms:30000}")
    public void sweepApprovedReviews() {
        List<FeedbackFlow> flows = flowMapper.selectList(new QueryWrapper<FeedbackFlow>()
                .eq("analyze_status", FeedbackAnalyzeStatus.REVIEW_CREATED.getValue())
                .eq("status", FeedbackStatus.REVIEWING.getValue())
                .last("LIMIT " + SCAN_LIMIT));
        for (FeedbackFlow flow : flows) {
            processFlowReview(flow);
        }
    }

    private void processFlowReview(FeedbackFlow flow) {
        List<Long> taskIds = FeedbackJsonUtil.parseLongList(flow.getReviewTaskIds());
        if (taskIds.isEmpty()) {
            return;
        }
        var tasks = reviewTaskAdapter.listByIds(taskIds);
        if (tasks.isEmpty()) {
            return;
        }
        boolean anyApproved = false;
        boolean anyRejected = false;
        boolean anyPending = false;
        for (var task : tasks) {
            if (ReviewTaskAdapter.isApproved(task)) {
                anyApproved = true;
            } else if (ReviewTaskAdapter.isRejected(task)) {
                anyRejected = true;
            } else {
                anyPending = true;
            }
        }
        if (anyApproved && !anyRejected && !anyPending) {
            // 全部 approved（无 rejected、无 pending）→ 置 APPROVED 并自动回写（BR-001）
            flow.setStatus(FeedbackStatus.APPROVED.getValue());
            flow.setUpdateTime(new Date());
            flowMapper.updateById(flow);
            try {
                writebackService.writeback(flow.getId(), false);
            } catch (Exception e) {
                log.warn("feedback: 调度器自动回写失败 flowId={}: {}",
                        flow.getId(), FeedbackSafeText.truncate(e.getMessage(), 200));
            }
        } else if (anyRejected && !anyApproved && !anyPending) {
            // 全部 rejected → 拒绝闭环（不写知识）
            flow.setStatus(FeedbackStatus.REJECTED.getValue());
            flow.setErrorMsg("审核全部拒绝，闭环终止");
            flow.setUpdateTime(new Date());
            flowMapper.updateById(flow);
        } else if (anyRejected && anyApproved && !anyPending) {
            // 混合终态（部分 approved + 部分 rejected）：禁止回写未批准建议（BR-001），
            // 同时避免流永久停留 REVIEWING——显式落 REJECTED 转人工确认处理
            flow.setStatus(FeedbackStatus.REJECTED.getValue());
            flow.setErrorMsg("审核部分批准、部分拒绝：未自动回写，请人工确认后重新发起（BR-001 防污染）");
            flow.setUpdateTime(new Date());
            flowMapper.updateById(flow);
        }
        // 存在 pending（无论是否已有 approved/rejected）：等待剩余审批完成，不重复触发
    }

    /** 周期：fb_wiki_writeback 重试扫描 + 远端任务轮询（默认 60s） */
    @Scheduled(fixedDelayString = "${agenthub.feedback.wiki-retry-interval-ms:60000}")
    public void sweepWikiWritebacks() {
        retryPendingWikiTasks();
        pollRemoteWikiTasks();
    }

    private void retryPendingWikiTasks() {
        List<WikiWritebackTask> tasks = wikiTaskMapper.selectRetryTasks(new Date(), SCAN_LIMIT);
        for (WikiWritebackTask task : tasks) {
            retryWikiTask(task);
        }
    }

    private void retryWikiTask(WikiWritebackTask task) {
        int retry = task.getRetryCount() == null ? 0 : task.getRetryCount();
        if (retry >= 5) {
            // 重试超限：置 FAILED（告警/转人工由运营页展示）
            wikiTaskMapper.markFailed(task.getId(),
                    FeedbackSafeText.truncate(task.getLastError(), 200), null, new Date());
            return;
        }
        try {
            wikiTaskMapper.markRunning(task.getId(), new Date());
            wikiWritebackAdapter.writePage(task.getWikiSpace(), task.getPagePath(), task.getPageContent());
            try {
                SyncTaskVO sync = wikiWritebackAdapter.syncPages(task.getWikiSpace());
                if (LlmWikiWritebackAdapter.isTaskCompleted(sync)) {
                    wikiTaskMapper.markSuccess(task.getId(), new Date());
                } else {
                    wikiTaskMapper.markRemoteTask(task.getId(),
                            sync == null ? null : sync.taskId(), new Date());
                }
            } catch (Exception e) {
                // sync 失败不阻塞：写页已成功
                wikiTaskMapper.markSuccess(task.getId(), new Date());
            }
        } catch (Exception e) {
            log.warn("feedback: wiki 出站重试失败 taskId={}: {}",
                    task.getId(), FeedbackSafeText.truncate(e.getMessage(), 200));
            long backoff = 30_000L * (1L << Math.min(retry, 5));
            Date nextRetry = new Date(System.currentTimeMillis() + backoff);
            wikiTaskMapper.markFailed(task.getId(),
                    FeedbackSafeText.truncate(e.getMessage(), 200), nextRetry, new Date());
        }
    }

    private void pollRemoteWikiTasks() {
        List<WikiWritebackTask> running = wikiTaskMapper.selectRunningRemoteTasks(SCAN_LIMIT);
        for (WikiWritebackTask task : running) {
            try {
                SyncTaskVO remote = wikiWritebackAdapter.getSyncTask(task.getRemoteTaskId());
                if (remote == null) {
                    continue;
                }
                if (LlmWikiWritebackAdapter.isTaskCompleted(remote)) {
                    wikiTaskMapper.markSuccess(task.getId(), new Date());
                } else if (LlmWikiWritebackAdapter.isTaskFailed(remote)) {
                    wikiTaskMapper.markFailed(task.getId(),
                            FeedbackSafeText.truncate(remote.error(), 200),
                            new Date(System.currentTimeMillis() + 60_000L), new Date());
                }
                // running 继续等待
            } catch (Exception e) {
                log.warn("feedback: wiki 远端任务轮询失败 taskId={}: {}",
                        task.getId(), FeedbackSafeText.truncate(e.getMessage(), 200));
            }
        }
    }
}
