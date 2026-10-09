package com.agenthub.ai.rcagent.attribution.dto;

import com.agenthub.ai.rcagent.attribution.constant.CompareType;
import com.agenthub.ai.rcagent.attribution.constant.TriggerType;
import com.agenthub.ai.rcagent.attribution.model.ConfigOverride;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 发起归因入参（POST /api/v1/attribution/analyze，api-contract 接口 1）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionAnalyzeRequest {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 指标名（必填，如 投诉量/收入） */
    private String metricName;

    /** 本期时间窗开始（YYYY-MM-DD，必填） */
    private String currentStart;

    /** 本期时间窗结束（必填） */
    private String currentEnd;

    /** 基期时间窗开始（必填） */
    private String baseStart;

    /** 基期时间窗结束（必填） */
    private String baseEnd;

    /** 对比方式：MOM/YOY/CUSTOM，默认 MOM */
    private String compareType = CompareType.MOM.name();

    /** 触发方式：METRIC_ANOMALY/USER_FOLLOW_UP，默认 USER_FOLLOW_UP */
    private String triggerType = TriggerType.USER_FOLLOW_UP.name();

    /** 候选下钻维度（BRANCH/TIME_SLOT/BIZ_TYPE/CHANNEL 或自定义 schema 字段语义） */
    private List<String> dimensions;

    /** 用户追问原文（展示/回看，落库截断） */
    private String question;

    /** 追问上下文（上一次结果摘要/已展示内容，FR-008 防重复归因） */
    private Map<String, String> queryContext;

    /** 显式指定 kb_metric id */
    private Long metricKbId;

    /** 规则覆盖（threshold_rate/min_delta_abs/max_depth/max_queries/top_segments/positive_majority/mvp_review_all） */
    private ConfigOverride configOverrides;

    /** 关联工作流会话 thread_id（module-001 编排时传入） */
    private String threadId;

    /** 操作人（默认 BaseContext） */
    private Long operatorId;
}
