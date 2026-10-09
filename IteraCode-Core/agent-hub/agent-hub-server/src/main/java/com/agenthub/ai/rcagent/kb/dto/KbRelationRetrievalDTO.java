package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 接口23 表关系检索召回入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbRelationRetrievalDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 表 A（table_a 或 table_b 至少一个） */
    private String tableA;

    /** 表 B */
    private String tableB;

    /** FK/REFERENCE */
    private String relationType;

    /** topK，默认 10 */
    private Integer topK;

    public int topK() {
        return topK == null || topK < 1 ? 10 : Math.min(topK, 100);
    }
}
