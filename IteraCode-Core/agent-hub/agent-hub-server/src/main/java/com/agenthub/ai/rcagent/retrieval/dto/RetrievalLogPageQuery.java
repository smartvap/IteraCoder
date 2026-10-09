package com.agenthub.ai.rcagent.retrieval.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 融合检索质量日志分页查询（接口2 POST /api/v1/retrieval/log/page）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalLogPageQuery {

    /** 数据源实例过滤（可空，默认全部） */
    private String dsId;

    /** 工作流会话过滤 */
    private String threadId;

    /** 冷启动信号过滤（0/1） */
    private Integer coldStart;

    /** 开始时间 yyyy-MM-dd HH:mm:ss */
    private String startTime;

    /** 结束时间 yyyy-MM-dd HH:mm:ss */
    private String endTime;

    /** 页码，默认 1 */
    private Integer page = 1;

    /** 每页条数，默认 10 */
    private Integer pageSize = 10;

    public int page() {
        return page == null || page < 1 ? 1 : page;
    }

    public int pageSize() {
        return pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 100);
    }
}
