package com.agenthub.ai.rcagent.feedback.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 触发回写结果 VO（接口7 data）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackWritebackVO {

    private Long flowId;

    /** CLOSED/FAILED/WRITTEN_BACK */
    private String status;

    /** 库 A 成功写回对象数 */
    private Long aWriteback;

    /** 库 B 成功写回页面数 */
    private Long bWriteback;

    /** A/B/AB */
    private String repo;

    /** 失败原因（截断，若部分失败） */
    private List<String> failedReasons;
}
