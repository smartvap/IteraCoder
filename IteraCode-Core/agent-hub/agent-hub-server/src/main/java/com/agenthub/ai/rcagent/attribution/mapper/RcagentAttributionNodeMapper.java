package com.agenthub.ai.rcagent.attribution.mapper;

import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionNode;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * rcagent_attribution_node 下钻节点 Mapper。
 */
@Mapper
public interface RcagentAttributionNodeMapper extends BaseMapper<RcagentAttributionNode> {

    /** 按任务层序读取下钻树（含 ROOT/GROUP_DRILL） */
    List<RcagentAttributionNode> selectByTaskOrdered(@Param("taskId") String taskId);
}
