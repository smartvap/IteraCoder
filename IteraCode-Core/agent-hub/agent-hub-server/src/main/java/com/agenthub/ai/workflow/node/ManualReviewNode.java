package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.HashMap;
import java.util.Map;

/**
 * 推理结果人工审核节点：整理待审核内容，供 StateGraph interruptAfter 暂停后人工介入
 */
@Slf4j
public class ManualReviewNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        Object parallelResultObj = state.value(RdWorkflowKeys.PARALLEL_REASONING_RESULT, "");
        Object decompositionObj = state.value(RdWorkflowKeys.DECOMPOSITION_RESULT, "");

        String parallelResult = extractMessageContent(parallelResultObj);
        String decomposition = extractMessageContent(decompositionObj);

        String reviewContent = """
                ===== 需求拆解结果 =====
                %s

                ===== 多模型并行推理结果 =====
                %s

                请人工审核以上推理/设计结果，决策：APPROVED(通过) / SENT_BACK(驳回) / TERMINATED(拒绝)
                """.formatted(decomposition, parallelResult);

        log.info("人工审核节点：已准备待审核内容，等待 StateGraph 暂停");

        Map<String, Object> result = new HashMap<>();
        result.put(RdWorkflowKeys.REVIEW_CONTENT, reviewContent);
        result.put(RdWorkflowKeys.WORKFLOW_STATUS, "WAITING_REVIEW");
        result.put(RdWorkflowKeys.WORKFLOW_MESSAGE, "流程已暂停，等待人工审核推理结果");
        return result;
    }

    private String extractMessageContent(Object obj) {
        if (obj == null) {
            return "";
        }
        if (obj instanceof AssistantMessage assistantMessage) {
            return assistantMessage.getText();
        }
        return obj.toString();
    }
}
