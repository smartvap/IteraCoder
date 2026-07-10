package com.agenthub.ai.workflow.vo;

import com.agenthub.ai.workflow.constant.RdWorkflowStatus;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class RdWorkflowResultVO {
    private String threadId;
    private RdWorkflowStatus status;
    private String message;
    private boolean interrupted;
    private String interruptedNode;
    private Map<String, Object> state;
}
