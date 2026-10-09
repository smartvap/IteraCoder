package com.agenthub.ai.rcagent.attribution.mapper;

import com.agenthub.ai.rcagent.attribution.dto.AttributionTaskPageQuery;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * rcagent_attribution_task 归因任务 Mapper。
 */
@Mapper
public interface RcagentAttributionTaskMapper extends BaseMapper<RcagentAttributionTask> {

    /**
     * 归因历史分页（接口4）：按 ds_id/metric/触发/状态/结果类型/时间过滤。
     * <p>分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）；
     * 首参 IPage 触发拦截器，Service 侧已 clamp。</p>
     */
    IPage<RcagentAttributionTask> selectPageTasks(IPage<RcagentAttributionTask> page,
                                                  @Param("q") AttributionTaskPageQuery q);

    /**
     * 结论待审任务（CONCLUSION_REVIEWING，审核台待办）。
     */
    List<RcagentAttributionTask> selectReviewPendingTasks(@Param("dsId") String dsId,
                                                          @Param("taskId") String taskId);
}
