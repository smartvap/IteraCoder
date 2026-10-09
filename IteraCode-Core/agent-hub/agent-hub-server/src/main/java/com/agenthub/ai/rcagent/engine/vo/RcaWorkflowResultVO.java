package com.agenthub.ai.rcagent.engine.vo;

import com.agenthub.ai.rcagent.engine.constant.RcaWorkflowStatus;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * RCAgent 会话结果视图（平行 RdWorkflowResultVO）。
 */
@Data
@Builder
public class RcaWorkflowResultVO {

    /** 会话线程 ID */
    private String threadId;

    /** 六态状态 */
    private RcaWorkflowStatus status;

    /** 状态说明；终态优先读 metadata.remark 失败/终止原因 */
    private String message;

    /** 是否人工审核中断 */
    private boolean interrupted;

    /** 中断节点（rcagent_review / attribution_review） */
    private String interruptedNode;

    /** State 键值快照（sanitize 后可序列化） */
    private Map<String, Object> state;

    /** 本次绑定的系统编码（新增，可空，从 State SYSTEM_CODE 读取） */
    private String systemCode;

    /** 本次候选源集合（新增，有序、去重、主源在前；缺失时回落 [DS_ID]） */
    private List<String> dsIds;
}
