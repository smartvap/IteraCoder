package com.agenthub.ai.rcagent.kb.mapper;

import com.agenthub.ai.rcagent.kb.entity.KbReviewTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * kb_review_task 审核任务 Mapper。
 */
@Mapper
public interface KbReviewTaskMapper extends BaseMapper<KbReviewTask> {

    /**
     * 审核任务分页（接口12）：动态条件，create_time DESC。
     * <p>分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）；
     * 首参 IPage 触发拦截器，Service 侧已 clamp。</p>
     */
    IPage<KbReviewTask> selectPageTasks(IPage<KbReviewTask> page,
                                       @Param("dsId") String dsId,
                                       @Param("status") String status,
                                       @Param("taskType") String taskType,
                                       @Param("targetObjectType") String targetObjectType,
                                       @Param("reviewer") String reviewer,
                                       @Param("startTime") Date startTime,
                                       @Param("endTime") Date endTime);
}
