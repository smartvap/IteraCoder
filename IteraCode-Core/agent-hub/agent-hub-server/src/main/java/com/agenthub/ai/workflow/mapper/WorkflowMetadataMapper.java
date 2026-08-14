package com.agenthub.ai.workflow.mapper;

import com.agenthub.ai.workflow.entity.WorkflowMetadata;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface WorkflowMetadataMapper extends BaseMapper<WorkflowMetadata> {

    /**
     * 查该线程最新 checkpoint 的主键（确定性排序：saved_at DESC + checkpoint_id DESC）。
     * 只查小字段主键，避免 state_data 大字段参与 ORDER BY 触发 Out of sort memory。
     */
    String selectLatestCheckpointId(@Param("threadName") String threadName);

    /** 按主键取 checkpoint 的 state_data（含 binaryPayload base64） */
    String selectCheckpointStateData(@Param("checkpointId") String checkpointId);
}
