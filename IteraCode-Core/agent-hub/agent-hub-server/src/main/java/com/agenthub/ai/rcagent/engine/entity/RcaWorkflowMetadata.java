package com.agenthub.ai.rcagent.engine.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * RCAgent 会话元数据（平行存量 WorkflowMetadata，扩展 ds_id/意图/风险/结果摘要）。
 *
 * <p>与 GRAPH_CHECKPOINT（二进制 checkpoint）互补，提供可 SQL 索引/查询/审计的文本字段。
 * 主键 thread_id 与图 checkpoint threadId 一致。</p>
 */
@TableName(value = "rcagent_workflow_metadata")
@Data
public class RcaWorkflowMetadata {

    /** 会话线程 ID（UUID 去掉横线 32 位），与 Graph checkpoint threadId 一致 */
    @TableId
    private String threadId;

    /** 本次会话绑定的业务数据源 ID（BR-001 显式绑定；只读引用不建外键） */
    private String dsId;

    /** 用户原始问题 */
    private String question;

    /** 意图识别结果：CHAT / QUERY / ATTRIBUTION */
    private String intentType;

    /** 会话状态：RUNNING/WAITING_REVIEW/SENT_BACK/COMPLETED/TERMINATED/FAILED */
    private String status;

    /** 最近一次写入的图节点名 */
    private String currentNode;

    /** 风险分级快照：BLOCK/LOW/MEDIUM/HIGH */
    private String riskLevel;

    /** 最近一次审核决策：APPROVED/SENT_BACK/TERMINATED */
    private String reviewDecision;

    /** 审核备注/打回意见 */
    private String reviewFeedback;

    /** 结果摘要（查询结果/归因结论摘要） */
    private String resultSummary;

    /** 发起会话的用户（BaseContext.getCurrentId()） */
    private Long creatorId;

    /** 备注（手动终止原因/失败原因/系统自动终止等） */
    private String remark;

    /** 创建时间 */
    private Date createTime;

    /** 最后更新时间 */
    private Date updateTime;
}
