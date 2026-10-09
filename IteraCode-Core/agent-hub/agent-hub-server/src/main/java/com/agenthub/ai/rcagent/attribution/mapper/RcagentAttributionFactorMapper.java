package com.agenthub.ai.rcagent.attribution.mapper;

import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionFactor;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * rcagent_attribution_factor 候选因子 Mapper。
 */
@Mapper
public interface RcagentAttributionFactorMapper extends BaseMapper<RcagentAttributionFactor> {

    /** 按任务读取全部因子（结论组装） */
    List<RcagentAttributionFactor> selectByTaskOrdered(@Param("taskId") String taskId);

    /** 按下钻节点读取因子（node 展示） */
    List<RcagentAttributionFactor> selectByNodeOrdered(@Param("nodeId") String nodeId);
}
