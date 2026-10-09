package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口24 语义知识检索召回入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbSemanticRetrievalDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 关键词 */
    private String keyword;

    /** 知识库 B 空间名过滤 */
    private String spaceName;

    /** METRIC/CONCEPT/ALL，默认 ALL */
    private String category;

    /** topK，默认 10 */
    private Integer topK;

    public int topK() {
        return topK == null || topK < 1 ? 10 : Math.min(topK, 100);
    }
}
