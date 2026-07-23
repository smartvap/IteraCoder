package com.agenthub.ai.workflow.mapper;

import com.agenthub.ai.workflow.entity.WorkflowRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工作流记录 Mapper
 */
@Mapper
public interface WorkflowRecordMapper extends BaseMapper<WorkflowRecord> {
}
