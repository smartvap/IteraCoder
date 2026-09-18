package com.agenthub.ai.llmwiki.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * llm-wiki 异步任务视图（POST /pages/sync、POST /pages/upload 202 响应与 GET /tasks/{id} 共用）。
 * <p>
 * 对应 llm-wiki 返回：{task_id, kind, space, status, created_at, finished_at, result, error}；
 * result 为 completed 时的嵌套 JSON（sync: added/updated/deleted/skipped/errors；upload: path/content_hash/chunk_count/created），
 * 用 Object 原样反序列化/序列化，前端按需取值。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SyncTaskVO(
        @JsonProperty("task_id") String taskId,
        String kind,
        String space,
        String status,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("finished_at") String finishedAt,
        Object result,
        String error
) {
}
