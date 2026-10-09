package com.agenthub.ai.rcagent.retrieval.mapper;

import com.agenthub.ai.rcagent.retrieval.entity.RcagentRetrievalLog;
import com.agenthub.ai.rcagent.retrieval.vo.RetrievalDailyStatVO;
import com.agenthub.ai.rcagent.retrieval.vo.RetrievalStatsOverviewVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * rcagent_retrieval_log 质量观测日志 Mapper。
 */
@Mapper
public interface RcagentRetrievalLogMapper extends BaseMapper<RcagentRetrievalLog> {

    /**
     * 检索质量日志分页（接口2）：按 ds_id/thread_id/冷启动/时间过滤，create_time DESC。
     * <p>分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）；
     * 首参 IPage 触发拦截器，Service 侧已 clamp。</p>
     */
    IPage<RcagentRetrievalLog> selectPageLogs(IPage<RcagentRetrievalLog> page,
                                             @Param("dsId") String dsId,
                                             @Param("threadId") String threadId,
                                             @Param("coldStart") Integer coldStart,
                                             @Param("startTime") Date startTime,
                                             @Param("endTime") Date endTime);

    /**
     * 检索质量总览聚合（接口3）：单行 overview。
     */
    RetrievalStatsOverviewVO selectStatsOverview(@Param("dsId") String dsId,
                                                 @Param("startTime") Date startTime,
                                                 @Param("endTime") Date endTime);

    /**
     * 检索质量按日/周聚合明细（接口3）。
     */
    List<RetrievalDailyStatVO> selectStatsDaily(@Param("dsId") String dsId,
                                                @Param("startTime") Date startTime,
                                                @Param("endTime") Date endTime,
                                                @Param("granularity") String granularity);
}
