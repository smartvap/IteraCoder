package com.agenthub.ai.workflow.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

@Data
@Builder
public class WorkflowRecordVO {

    private String threadId;
    private String requirement;
    private String reviewFeedback;
    private String status;
    private Date createTime;
    private Date updateTime;
    private String remark;
}
