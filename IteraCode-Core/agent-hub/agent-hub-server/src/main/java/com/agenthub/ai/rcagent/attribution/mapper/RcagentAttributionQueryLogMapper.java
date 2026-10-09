package com.agenthub.ai.rcagent.attribution.mapper;

import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionQueryLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * rcagent_attribution_query_log 归因查询审计 Mapper。
 */
@Mapper
public interface RcagentAttributionQueryLogMapper extends BaseMapper<RcagentAttributionQueryLog> {

    /** 按任务读取全部归因查询审计 */
    List<RcagentAttributionQueryLog> selectByTaskOrdered(@Param("taskId") String taskId);

    /**
     * 归因查询待审列表（审核台）：任务处于 QUERY_REVIEWING 且 review_status=NONE 的查询。
     * SQL 联表 task 校验任务状态与 ds 过滤。
     */
    List<RcagentAttributionQueryLog> selectReviewPendingQueries(@Param("dsId") String dsId,
                                                                @Param("taskId") String taskId);
}
