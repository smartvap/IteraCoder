package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 分页入参公共字段（POST body 携带）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbPageDTO {

    /** 页码，默认 1 */
    private Integer page = 1;

    /** 每页条数，默认 10 */
    private Integer pageSize = 10;

    public int page() {
        return page == null || page < 1 ? 1 : page;
    }

    public int pageSize() {
        return pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
    }
}
