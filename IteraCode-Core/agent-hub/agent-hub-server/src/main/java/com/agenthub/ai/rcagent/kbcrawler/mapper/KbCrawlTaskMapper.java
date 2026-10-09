package com.agenthub.ai.rcagent.kbcrawler.mapper;

import com.agenthub.ai.rcagent.kbcrawler.entity.KbCrawlTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * kb_crawl_task 采集任务状态 Mapper。
 */
@Mapper
public interface KbCrawlTaskMapper extends BaseMapper<KbCrawlTask> {

    /**
     * 任务分页/列表条件查询（分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接
     * limit/count，SQL 不写 limit；首参 IPage 触发拦截器）。
     */
    IPage<KbCrawlTask> selectTaskList(IPage<KbCrawlTask> page,
                                      @Param("dsId") String dsId,
                                      @Param("status") String status);

    /**
     * 调度器扫描：pause_flag=0 且非运行中的任务（按 ds_id 暂停维度过滤）。
     */
    List<KbCrawlTask> selectSchedulable(@Param("status") String status);

    /**
     * 到点抢占：仅当任务仍可调度且已到点（或尚无调度基线）时推进 next_run_time。
     *
     * @param id   任务行 ID
     * @param next 抢占后写回的下次到点时间
     * @param now  当前时间（用于到点判定）
     * @return 受影响行数（1 = 抢占成功，0 = 被其它路径抢占/已暂停/运行中/未到点）
     */
    int claimDue(@Param("id") Long id, @Param("next") Date next, @Param("now") Date now);
}
