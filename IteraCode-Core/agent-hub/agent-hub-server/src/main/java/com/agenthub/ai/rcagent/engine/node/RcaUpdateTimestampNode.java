package com.agenthub.ai.rcagent.engine.node;

import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowKeys;
import com.agenthub.ai.rcagent.engine.entity.RcaWorkflowMetadata;
import com.agenthub.ai.rcagent.engine.mapper.RcaWorkflowMetadataMapper;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;

import java.util.Date;
import java.util.Map;

/**
 * RCAgent 时间戳更新节点：异步刷新 rcagent_workflow_metadata.update_time / current_node。
 */
@Slf4j
public class RcaUpdateTimestampNode implements NodeAction {

    private final RcaWorkflowMetadataMapper metadataMapper;
    private final String nodeName;

    public RcaUpdateTimestampNode(RcaWorkflowMetadataMapper metadataMapper, String nodeName) {
        this.metadataMapper = metadataMapper;
        this.nodeName = nodeName;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String threadId = state.value(RcaWorkflowKeys.THREAD_ID_KEY)
                .map(Object::toString).orElse(null);
        if (threadId == null) {
            return Map.of();
        }
        try {
            RcaWorkflowMetadata meta = metadataMapper.selectById(threadId);
            if (meta != null) {
                meta.setUpdateTime(new Date());
                if (nodeName != null && !nodeName.isBlank()) {
                    meta.setCurrentNode(nodeName);
                }
                metadataMapper.updateById(meta);
            }
        } catch (Exception e) {
            log.warn("RCAgent 更新时间戳失败: threadId={}, node={}", threadId, nodeName, e);
        }
        return Map.of();
    }
}
