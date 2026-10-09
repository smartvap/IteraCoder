package com.agenthub.ai.rcagent.attribution.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 归因审核分页查询（POST /api/v1/attribution/review/page，api-contract 接口 5）。
 *
 * <p>按 api-contract 请求字段为 snake_case（review_object/task_id/ds_id/pending_only/page_size 等），
 * 需与其余入参 DTO 保持一致使用类级 snake_case 命名策略，否则契约字段无法反序列化。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionReviewPageQuery {

    /** QUERY / CONCLUSION */
    private String reviewObject;

    /** 指定任务过滤 */
    private String taskId;

    /** 数据源过滤 */
    private String dsId;

    /** APPROVE/REJECT（过滤已处理） */
    private String decision;

    /** 仅待审（默认 true） */
    private Boolean pendingOnly = true;

    /** 页码 */
    private Integer page = 1;

    /** 每页条数（1~100） */
    private Integer pageSize = 10;

    public int page() {
        return page == null || page < 1 ? 1 : page;
    }

    public int pageSize() {
        return pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 100);
    }

    public boolean pendingOnlyValue() {
        return !Boolean.FALSE.equals(pendingOnly);
    }
}
