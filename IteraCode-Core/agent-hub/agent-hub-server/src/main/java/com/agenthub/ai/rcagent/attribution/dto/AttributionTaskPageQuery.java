package com.agenthub.ai.rcagent.attribution.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 归因任务历史分页查询（POST /api/v1/attribution/task/page，api-contract 接口 4）。
 *
 * <p>按 api-contract 请求字段为 snake_case（ds_id/metric_name/trigger_type/start_date/page_size 等），
 * 需与其余入参 DTO 保持一致使用类级 snake_case 命名策略，否则契约字段无法反序列化。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionTaskPageQuery {

    /** 数据源过滤 */
    private String dsId;

    /** 指标模糊过滤 */
    private String metricName;

    /** METRIC_ANOMALY/USER_FOLLOW_UP */
    private String triggerType;

    /** 任务状态 */
    private String status;

    /** 结果类型 */
    private String resultStatus;

    /** create_time 区间起（yyyy-MM-dd） */
    private String startDate;

    /** create_time 区间止（yyyy-MM-dd） */
    private String endDate;

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
}
