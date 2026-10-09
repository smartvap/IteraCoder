package com.agenthub.ai.rcagent.kb.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 接口21 SQL 模板检索召回入参。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbTemplateRetrievalDTO {

    /** 数据源实例 ID（必填） */
    private String dsId;

    /** 业务动作精确/前缀 */
    private String bizAction;

    /** 样本问法/归一化问题关键词 */
    private String questionKeyword;

    /** 涉及表名过滤 */
    private String tableName;

    /** topK，默认 10 */
    private Integer topK;

    /** 最小置信度过滤，默认不限制（仍 verified=1） */
    private BigDecimal minConfidence;

    public int topK() {
        return topK == null || topK < 1 ? 10 : Math.min(topK, 100);
    }
}
