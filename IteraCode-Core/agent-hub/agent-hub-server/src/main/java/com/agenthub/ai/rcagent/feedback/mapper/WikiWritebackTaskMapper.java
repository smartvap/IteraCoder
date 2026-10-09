package com.agenthub.ai.rcagent.feedback.mapper;

import com.agenthub.ai.rcagent.feedback.dto.FeedbackCountItem;
import com.agenthub.ai.rcagent.feedback.entity.WikiWritebackTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * fb_wiki_writeback B 库出站任务 Mapper。
 *
 * <p>重试扫描（PENDING/FAILED 到点任务）由调度器驱动；RUNNING 任务按 remote_task_id
 * 轮询远端 llm-wiki 任务状态。</p>
 */
@Mapper
public interface WikiWritebackTaskMapper extends BaseMapper<WikiWritebackTask> {

    /**
     * 重试扫描：PENDING（未到重试时间默认立即可试）/ FAILED 且 next_retry_time 为空或已到点。
     * 按 retry_count 升序优先处理失败次数少的任务。
     */
    List<WikiWritebackTask> selectRetryTasks(@Param("now") Date now,
                                             @Param("limit") int limit);

    /**
     * 轮询远端任务：status=RUNNING 且 remote_task_id 非空（调度器定期查 llm-wiki /tasks/{id}）。
     */
    List<WikiWritebackTask> selectRunningRemoteTasks(@Param("limit") int limit);

    /**
     * B 库出站状态分布（接口4 writebackCount）：status GROUP BY。
     */
    List<FeedbackCountItem> countWikiByStatus(@Param("dsId") String dsId);

    /**
     * 按反馈查询最新一条出站任务（详情页展示 B 库进度）。
     */
    WikiWritebackTask selectLatestByFeedback(@Param("dsId") String dsId,
                                             @Param("feedbackId") Long feedbackId);

    /** 置 RUNNING（开始写 llm-wiki） */
    int markRunning(@Param("id") Long id, @Param("now") Date now);

    /** 记录远端同步任务 id 并保持 RUNNING（等待调度器轮询远端终态） */
    int markRemoteTask(@Param("id") Long id,
                       @Param("remoteTaskId") String remoteTaskId,
                       @Param("now") Date now);

    /** 置 SUCCESS（页面写入 + 远端任务完成） */
    int markSuccess(@Param("id") Long id, @Param("now") Date now);

    /** 置 FAILED（可重试，next_retry_time 指数退避由服务层计算） */
    int markFailed(@Param("id") Long id,
                   @Param("lastError") String lastError,
                   @Param("nextRetryTime") Date nextRetryTime,
                   @Param("now") Date now);

    /** 重试成功后复用：重新置 PENDING 由调度器执行出站 */
    int markPending(@Param("id") Long id, @Param("retryCount") Integer retryCount,
                    @Param("nextRetryTime") Date nextRetryTime,
                    @Param("now") Date now);
}
