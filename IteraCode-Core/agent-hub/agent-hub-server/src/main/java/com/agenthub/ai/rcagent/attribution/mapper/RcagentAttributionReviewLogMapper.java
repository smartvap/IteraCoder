package com.agenthub.ai.rcagent.attribution.mapper;

import com.agenthub.ai.rcagent.attribution.dto.AttributionReviewPageQuery;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionReviewLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * rcagent_attribution_review_log 审核留痕 Mapper。
 */
@Mapper
public interface RcagentAttributionReviewLogMapper extends BaseMapper<RcagentAttributionReviewLog> {

    /**
     * 已审核留痕分页（审核台历史）：join task 以支持 ds_id 过滤。
     * <p>分页由 MyBatis-Plus PaginationInnerInterceptor 自动拼接 limit/count（SQL 不写 limit）；
     * 首参 IPage 触发拦截器，Service 侧已 clamp。</p>
     */
    IPage<RcagentAttributionReviewLog> selectPageReviews(IPage<RcagentAttributionReviewLog> page,
                                                         @Param("q") AttributionReviewPageQuery q);
}
