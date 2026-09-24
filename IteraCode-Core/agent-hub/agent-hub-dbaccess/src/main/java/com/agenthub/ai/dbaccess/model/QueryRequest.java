package com.agenthub.ai.dbaccess.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询执行请求（data-model.md t_query_request，Executor 统一入参，不落库）。
 *
 * <p>限制取值规则：行数与时间限制取「请求值 ∩ 数据源配置值 ∩ 模块默认值」中最严格者。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryRequest {

    /** 数据源标识（必填） */
    private String dsId;

    /** 待执行 SQL（必填，仅只读 SELECT/EXPLAIN） */
    private String sql;

    /** 最大返回行数（可空，默认取模块配置） */
    private Integer maxRows;

    /** 最大执行秒数（可空，默认取模块配置） */
    private Integer maxSeconds;

    /** 敏感字段策略（可空，默认取模块/数据源配置） */
    private SensitivePolicy sensitivePolicy;

    /** 是否仅执行 EXPLAIN 计划（可空，默认 false） */
    private Boolean explainOnly;

    public boolean isExplainOnly() {
        return Boolean.TRUE.equals(explainOnly);
    }
}
