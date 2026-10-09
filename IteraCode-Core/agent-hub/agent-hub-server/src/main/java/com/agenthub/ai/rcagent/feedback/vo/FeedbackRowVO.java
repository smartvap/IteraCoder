package com.agenthub.ai.rcagent.feedback.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.Date;

/**
 * 反馈闭环列表行 VO（接口3 records）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackRowVO {

    private Long flowId;

    private Long feedbackId;

    private String dsId;

    private String sessionId;

    /** 原始问题（摘要截断） */
    private String question;

    private String feedbackType;

    private String changeType;

    private String status;

    private String analyzeStatus;

    /** 关联审核任务数 */
    private Integer reviewTaskCount;

    private String writebackRepo;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;
}
