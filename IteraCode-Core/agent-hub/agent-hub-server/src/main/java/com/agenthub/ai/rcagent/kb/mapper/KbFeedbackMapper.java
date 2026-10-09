package com.agenthub.ai.rcagent.kb.mapper;

import com.agenthub.ai.rcagent.kb.entity.KbFeedback;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

/**
 * kb_feedback 反馈记录 Mapper。
 */
@Mapper
public interface KbFeedbackMapper extends BaseMapper<KbFeedback> {

    /**
     * 反馈分页（接口26）：动态条件，create_time DESC。
     * <p>分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）；
     * 首参 IPage 触发拦截器，Service 侧已 clamp。</p>
     */
    IPage<KbFeedback> selectPageFeedbacks(IPage<KbFeedback> page,
                                         @Param("dsId") String dsId,
                                         @Param("status") String status,
                                         @Param("sessionId") String sessionId,
                                         @Param("startTime") Date startTime,
                                         @Param("endTime") Date endTime);
}
