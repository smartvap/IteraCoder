package com.agenthub.ai.rcagent.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 反馈闭环处理流（fb_feedback_flow）。
 *
 * <p>每条反馈一条处理流（1:1 逻辑关联 module-004 kb_feedback.id，不建物理外键）。
 * JSON 文本列（diff_summary/suggestions/review_task_ids/signal_keys）以 TEXT 存
 * fastjson2 JSON 字符串，Service 内序列化/解析（data-model 注意事项 3）。</p>
 */
@Data
@TableName(value = "fb_feedback_flow")
public class FeedbackFlow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 数据源实例 ID（BR-005 强隔离） */
    private String dsId;

    /** module-004 kb_feedback.id（逻辑引用，唯一） */
    private Long feedbackId;

    /** 关联会话/工作流 thread_id */
    private String sessionId;

    /** 反馈类型 POSITIVE/NEGATIVE/CORRECTION/EXPLANATION */
    private String feedbackType;

    /** 修改类型（AST diff/LLM 判定） */
    private String changeType;

    /** 修改类型判定置信度（0~1） */
    private BigDecimal changeConfidence;

    /** AST diff 摘要 JSON（不含全量 SQL 原文） */
    private String diffSummary;

    /** KnowledgeBuilder 建议快照 JSON 数组 */
    private String suggestions;

    /** 关联审核任务 id JSON 数组（module-004 kb_review_task.id） */
    private String reviewTaskIds;

    /** 分析阶段 */
    private String analyzeStatus;

    /** 闭环状态 */
    private String status;

    /** 已完成的回写目标（多值逗号分隔，如 A,B） */
    private String writebackRepo;

    /** 是否含库 B 回写需求（0/1） */
    private Integer wikiWritebackFlag;

    /** 是否已向 module-004 confidence 推送信号（0/1） */
    private Integer confidencePushed;

    /** 已使用置信度幂等键 JSON 数组 */
    private String signalKeys;

    /** 库 A 回写是否完成（0/1） */
    private Integer aWritebackDone;

    /** 库 B 回写是否完成（0/1） */
    private Integer bWritebackDone;

    /** 最近错误信息（截断，不含连接串/Token） */
    private String errorMsg;

    /** 失败重试次数 */
    private Integer retryCount;

    private Date createTime;

    private Date updateTime;
}
