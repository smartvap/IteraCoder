package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口22 业务链路检索召回入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbBizLinkRetrievalDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 业务操作关键词 */
    private String operationKeyword;

    /** service 类 */
    private String serviceClass;

    /** 涉及表过滤 */
    private String tableName;

    /** topK，默认 10 */
    private Integer topK;

    public int topK() {
        return topK == null || topK < 1 ? 10 : Math.min(topK, 100);
    }
}
