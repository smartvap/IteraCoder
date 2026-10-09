package com.agenthub.ai.rcagent.retrieval.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 融合检索主结果（RetrievalService.search 出参，api-contract 内部服务契约）。
 *
 * <p>统一上下文 items + coverage + coldStart + degradations + meta；
 * HTTP 接口 1 由 {@link com.agenthub.ai.rcagent.retrieval.vo.RetrievalSearchVO} 包装为 REST 响应。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalResult {

    /** 本次检索请求标识 */
    private String requestId;

    /** 数据源实例 ID */
    private String dsId;

    /** 统一检索上下文（分组视图） */
    private RetrievalContextVO context;

    /** 全量统一命中列表（等价 context.items 展开，已排序去重） */
    private List<RetrievalHit> items = new ArrayList<>();

    /** 检索覆盖率 */
    private RetrievalCoverage coverage;

    /** 冷启动信号（FR-006 关键要素无高置信命中时 true） */
    private boolean coldStart;

    /** 冷启动原因（缺失项说明） */
    private String coldStartReason;

    /** 降级提示（如 llm-wiki 不可用） */
    private List<String> degradations = new ArrayList<>();

    /** 元信息（各通道命中数、上下文条目数、耗时、space_used） */
    private RetrievalMeta meta;
}
