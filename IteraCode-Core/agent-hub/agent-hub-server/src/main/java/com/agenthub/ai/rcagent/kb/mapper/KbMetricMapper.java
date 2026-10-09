package com.agenthub.ai.rcagent.kb.mapper;

import com.agenthub.ai.rcagent.kb.entity.KbMetric;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * kb_metric 指标定义 Mapper。
 */
@Mapper
public interface KbMetricMapper extends BaseMapper<KbMetric> {

    /**
     * 语义检索-指标召回（接口24）。
     */
    List<KbMetric> selectRetrievalMetrics(@Param("dsId") String dsId,
                                          @Param("keyword") String keyword,
                                          @Param("spaceName") String spaceName,
                                          @Param("topK") int topK);
}
