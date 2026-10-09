package com.agenthub.ai.rcagent.crosssource.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 逐源 SQL 产物（内存模型 + State 内部键 {@code source_query_plans}，**不落库**）。
 *
 * <p>JSON 序列化后写入 State（逐源 {@code sqlText} 截断 {@code source-sql-max-len}）；
 * {@code sqlText} 为只读 SELECT，**仅审核通道 / 逐源执行可见**，禁止进入 SSE、合并结果、日志。
 * 字段见 {@code data-model.md §3.3}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceQuerySql {

    /** 会话线程 ID */
    private String threadId;

    /** 源序号（唯一对外标识；审核通道按序号分节展示，禁止暴露 dsId） */
    private Integer sourceIndex;

    /** 源 dsId（内部） */
    private String dsId;

    /** 源角色：PROBE（探测侧，默认左源）/ TARGET（被下推侧，默认右源） */
    private String sourceRole;

    /** 只读 SELECT（截断 source-sql-max-len；仅审核通道可见） */
    private String sqlText;

    /** 请求行数上限（实际生效由既有执行层 clamp） */
    private Integer rowLimit;

    /** 状态：READY / REGENERATED */
    private String status;
}
