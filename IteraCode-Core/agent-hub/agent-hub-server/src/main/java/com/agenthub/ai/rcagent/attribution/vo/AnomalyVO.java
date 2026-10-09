package com.agenthub.ai.rcagent.attribution.vo;

import com.agenthub.ai.rcagent.attribution.model.QualityFlags;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 异常判定摘要（api-contract data.anomaly，data-model §4.2 anomaly_json）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AnomalyVO {

    /** 是否业务真异常 */
    private boolean anomaly;

    /** 方向 UP/DOWN/NONE */
    private String direction;

    /** 本期总体值 */
    private BigDecimal curTotal;

    /** 基期总体值 */
    private BigDecimal baseTotal;

    /** 环比变化率 % */
    private BigDecimal rateMom;

    /** 同比变化率 % */
    private BigDecimal rateYoy;

    /** 严重度 HIGH/MEDIUM/LOW */
    private String severity;

    /** 趋势连续性 */
    private Double continuityRatio;

    /** 伪异常信号 */
    private QualityFlags pseudoFlags;

    /** 命中规则 */
    private List<String> rulesHit;

    /** 无异常/数据问题说明 */
    private String message;
}
