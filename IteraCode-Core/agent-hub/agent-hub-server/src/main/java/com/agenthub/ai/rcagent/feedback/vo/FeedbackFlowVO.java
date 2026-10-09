package com.agenthub.ai.rcagent.feedback.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 反馈闭环详情 VO（接口2 GET /api/v1/rca/feedback/{flowId}）。
 * diffSummary 为 JSON 反序列化对象（不含 SQL 原文），suggestions/reviewTaskIds 反序列化返回。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackFlowVO {

    private Long flowId;

    private Long feedbackId;

    private String dsId;

    private String sessionId;

    private String feedbackType;

    private String changeType;

    private BigDecimal changeConfidence;

    /** diff 摘要（changedTables/columns/addedWhere/changedJoin/aggregateChanged 等） */
    private Object diffSummary;

    /** 分析阶段 RECEIVED/DIFF_ANALYZING/SUGGESTING/MANUAL_REQUIRED/REVIEW_CREATED/ANALYZE_FAILED */
    private String analyzeStatus;

    /** 闭环状态 */
    private String status;

    /** 修正建议列表 */
    private List<SuggestionVO> suggestions;

    /** 关联审核任务 id（module-004 kb_review_task.id） */
    private List<Long> reviewTaskIds;

    /** A/B/AB */
    private String writebackRepo;

    /** 是否有 B 库出站及进度（见 fb_wiki_writeback） */
    private String wikiWritebackStatus;

    /** 最近错误（截断） */
    private String errorMsg;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date updateTime;
}
