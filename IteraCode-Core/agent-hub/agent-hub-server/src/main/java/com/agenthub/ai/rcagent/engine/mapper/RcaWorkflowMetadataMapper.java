package com.agenthub.ai.rcagent.engine.mapper;

import com.agenthub.ai.rcagent.engine.entity.RcaWorkflowMetadata;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * RCAgent 会话元数据 Mapper。
 *
 * <p>继承 BaseMapper 提供单表 CRUD；XML 提供 GRAPH_CHECKPOINT 确定性读
 * （绕过框架 MysqlSaver 同秒排序不稳定问题，与 workflow 的 WorkflowMetadataMapper 同款）。</p>
 */
@Mapper
public interface RcaWorkflowMetadataMapper extends BaseMapper<RcaWorkflowMetadata> {

    /**
     * 查该线程最新 checkpoint 的主键（确定性排序：saved_at DESC + checkpoint_id DESC）。
     * 只查小字段主键，避免 state_data 大字段参与 ORDER BY 触发 Out of sort memory。
     */
    String selectLatestCheckpointId(@Param("threadName") String threadName);

    /** 按主键取 checkpoint 的 state_data（含 binaryPayload base64） */
    String selectCheckpointStateData(@Param("checkpointId") String checkpointId);
}
