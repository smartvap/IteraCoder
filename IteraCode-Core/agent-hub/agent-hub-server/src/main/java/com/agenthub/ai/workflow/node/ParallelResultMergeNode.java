package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

/**
 * 并行推理结果聚合节点：根据配置的推理模型列表，动态合并多路 ReactAgent 输出为统一文本。
 * <p>
 * 不再硬编码两个推理 key，改为通过构造函数传入模型名列表，
 * 与 RdWorkflowGraphConfig 中的动态 Agent 创建机制保持一致。
 */
@Slf4j
public class ParallelResultMergeNode implements NodeAction {

    private final List<String> reasoningModels;

    public ParallelResultMergeNode(List<String> reasoningModels) {
        this.reasoningModels = reasoningModels;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        StringBuilder merged = new StringBuilder();

        for (String modelName : reasoningModels) {
            String key = toReasoningKey(modelName) + "_result";
            String result = extractText(state, key);

            merged.append("===== ").append(modelName).append(" 推理结果 =====\n");
            if (result.isBlank()) {
                merged.append("（该模型未产出结果）\n");
            } else {
                merged.append(result).append("\n");
            }
            merged.append("\n");
        }

        log.info("并行推理结果已聚合（模型数: {}）", reasoningModels.size());
        return Map.of(RdWorkflowKeys.PARALLEL_REASONING_RESULT, merged.toString());
    }

    /**
     * 将模型名转换为 State key 前缀，逻辑与 RdWorkflowGraphConfig.toAgentKey() 保持一致。
     */
    private static String toReasoningKey(String modelName) {
        return "reasoning_" + modelName.trim().replace("-", "_").replace(".", "_");
    }

    private String extractText(OverAllState state, String key) {
        return state.value(key)
                .map(value -> {
                    if (value instanceof Message message) {
                        return message.getText();
                    }
                    return value.toString();
                })
                .orElse("");
    }
}
