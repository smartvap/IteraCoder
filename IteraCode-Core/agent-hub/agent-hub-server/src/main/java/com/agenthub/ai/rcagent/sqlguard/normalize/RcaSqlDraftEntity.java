package com.agenthub.ai.rcagent.sqlguard.normalize;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RCAgent 结构化 SQL 草案实体（L1 结构化输出 / tool calling 参数载体，**内部 DTO，非 HTTP 响应模型**）。
 *
 * <p>用于 {@code ChatClient.entity(RcaSqlDraftEntity.class)} 或工具调用参数接收，字段：
 * {@code sql}（纯只读 SELECT 语句）与 {@code rationale}（生成理由，供审核通道参考）。</p>
 *
 * <p>安全（AGENTS.md 5.4）：本实体由 LLM 单次调用返回，仅内存态、不落库、不进入 SSE / HTTP 响应；
 * 回喂模型时只允许携带 {@code issue} 分类与中性提示，禁止拼接 {@code sql} 全文。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonClassDescription("只读 SQL 草案的结构化输出")
public class RcaSqlDraftEntity {

    /** 生成的只读 SELECT SQL（无 Markdown 围栏、无结尾分号） */
    @JsonProperty("sql")
    @JsonPropertyDescription("完整且语法正确的只读 SELECT 语句，不含 Markdown 代码围栏与结尾分号")
    private String sql;

    /** 生成理由（简要说明查询目标与口径，供人工审核参考） */
    @JsonProperty("rationale")
    @JsonPropertyDescription("生成该 SQL 的简要理由")
    private String rationale;
}
