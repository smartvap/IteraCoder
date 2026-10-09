package com.agenthub.ai.rcagent.feedback.mapper;

import com.agenthub.ai.rcagent.feedback.dto.FeedbackCountItem;
import com.agenthub.ai.rcagent.feedback.entity.FeedbackFlow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * fb_feedback_flow 反馈闭环处理流 Mapper。
 *
 * <p>分页（接口3）由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）；
 * 分组统计（接口4）按 feedback_type/status GROUP BY 返回强类型行（feedback 模块自有表，模块内 Mapper）。</p>
 */
@Mapper
public interface FeedbackFlowMapper extends BaseMapper<FeedbackFlow> {

    /**
     * 反馈闭环分页（接口3）：动态条件，create_time DESC。
     * <p>首参 IPage 触发 MP 拦截器，Service 侧已 clamp。</p>
     */
    IPage<FeedbackFlow> selectPageFlows(IPage<FeedbackFlow> page,
                                       @Param("dsId") String dsId,
                                       @Param("statusList") List<String> statusList,
                                       @Param("feedbackType") String feedbackType,
                                       @Param("changeType") String changeType,
                                       @Param("sessionId") String sessionId,
                                       @Param("startTime") Date startTime,
                                       @Param("endTime") Date endTime);

    /**
     * 类型分布统计（接口4）：feedback_type GROUP BY。
     */
    List<FeedbackCountItem> countByFeedbackType(@Param("dsId") String dsId,
                                                @Param("startTime") Date startTime,
                                                @Param("endTime") Date endTime);

    /**
     * 状态分布统计（接口4）：status GROUP BY。
     */
    List<FeedbackCountItem> countByStatus(@Param("dsId") String dsId,
                                          @Param("startTime") Date startTime,
                                          @Param("endTime") Date endTime);
}
