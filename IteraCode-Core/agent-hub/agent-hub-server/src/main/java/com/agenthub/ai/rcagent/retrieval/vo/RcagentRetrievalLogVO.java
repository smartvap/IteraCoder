package com.agenthub.ai.rcagent.retrieval.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 融合检索质量日志 VO（接口2 records，question 仅截断展示）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RcagentRetrievalLogVO {

    private Long id;

    private String dsId;

    private String threadId;

    /** 原始问题（截断展示，禁止明文全量，AGENTS.md 5.3） */
    private String question;

    /** 检索要素 JSON（截断展示） */
    private String queryElementsJson;

    /** 命中要素 JSON */
    private String matchedElementsJson;

    /** 未命中要素 JSON */
    private String missingElementsJson;

    /** 各路命中统计 JSON */
    private String channelHitJson;

    /** 上下文条目数 */
    private Integer contextItemCount;

    /** 上下文 token 估算 */
    private Integer contextTokenEstimate;

    /** 冷启动 0/1 */
    private Integer coldStart;

    /** 允许冷生成 0/1 */
    private Integer allowColdStart;

    /** 命中的 B 空间名 */
    private String spaceUsed;

    /** B 不可用 0/1 */
    private Integer kbBUnavailable;

    /** 启用 Rerank 0/1 */
    private Integer rerankEnabled;

    /** 耗时（毫秒） */
    private Integer latencyMs;

    /** P2 命中 SQL 模板 id */
    private Long matchedTemplateId;

    /** P2 命中正确 SQL 0/1 */
    private Integer matchedFlag;

    private String createTime;
}
