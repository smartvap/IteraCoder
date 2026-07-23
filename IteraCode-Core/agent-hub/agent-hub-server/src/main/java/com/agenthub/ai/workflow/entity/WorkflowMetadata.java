package com.agenthub.ai.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 工作流元数据记录。与 checkpoint 表（二进制 blob）互补，提供可查询的文本字段。
 *
 * @TableName workflow_metadata
 */
@TableName(value = "workflow_metadata")
@Data
public class WorkflowMetadata {

    /** 工作流线程 ID */
    @TableId
    private String threadId;

    /** 研发需求原文 */
    private String requirement;

    /** 审核备注 */
    private String reviewFeedback;

    /** 状态：RUNNING / WAITING_REVIEW / COMPLETED / TERMINATED / FAILED */
    private String status;

    /** 创建时间 */
    private Date createTime;

    /** 最后更新时间 */
    private Date updateTime;
}
