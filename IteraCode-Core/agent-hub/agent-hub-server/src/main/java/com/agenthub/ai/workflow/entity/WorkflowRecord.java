package com.agenthub.ai.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 工作流执行记录，用于历史查询。
 *
 * @TableName workflow_record
 */
@TableName(value = "workflow_record")
@Data
public class WorkflowRecord {

    /** 工作流线程 ID（UUID，全局唯一） */
    @TableId
    private String threadId;

    /** 研发需求原文 */
    private String requirement;

    /** 最后的审核备注（驳回/通过时用户填写的备注） */
    private String reviewFeedback;

    /** 工作流当前状态：RUNNING / WAITING_REVIEW / COMPLETED / TERMINATED / FAILED */
    private String status;

    /** 创建时间 */
    private Date createTime;

    /** 最后更新时间 */
    private Date updateTime;
}
