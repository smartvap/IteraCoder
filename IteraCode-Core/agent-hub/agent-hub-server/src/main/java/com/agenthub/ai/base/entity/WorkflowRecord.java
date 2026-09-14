package com.agenthub.ai.base.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@TableName(value = "workflow_record")
@Data
public class WorkflowRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String threadId;
    private Integer round;
    private String requirement;
    private String status;
    private String decompositionResult;
    private String reasoningResult;
    private String reviewDecision;
    private String reviewComment;
    private String codegenFiles;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalDurationMs;
    private String workflowMessage;
    private Date startTime;
    private Date endTime;
    private Date createTime;
}
