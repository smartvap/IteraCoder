package com.agenthub.ai.rcagent.attribution.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 异常识别结果（anomaly_json，FR-001 / data-model §4.2）。
 */
@Data
public class AnomalyResult {

    /** 是否业务真异常 */
    @JSONField(name = "is_anomaly")
    private boolean anomaly;

    /** 方向：UP / DOWN / NONE */
    private String direction;

    /** 本期总体值 */
    @JSONField(name = "cur_total")
    private BigDecimal curTotal;

    /** 基期总体值 */
    @JSONField(name = "base_total")
    private BigDecimal baseTotal;

    /** 环比变化率 %（null 表示本期=0 无法计算） */
    @JSONField(name = "rate_mom")
    private BigDecimal rateMom;

    /** 同比变化率 %（MVP 未执行多期对比时 null） */
    @JSONField(name = "rate_yoy")
    private BigDecimal rateYoy;

    /** 严重度：HIGH / MEDIUM / LOW */
    private String severity;

    /** 趋势连续性（MVP 预留） */
    @JSONField(name = "continuity_ratio")
    private Double continuityRatio;

    /** 伪异常信号（口径/数据问题） */
    @JSONField(name = "pseudo_flags")
    private QualityFlags pseudoFlags = new QualityFlags();

    /** 命中规则列表（如 MOM_RATE_GT_50） */
    @JSONField(name = "rules_hit")
    private List<String> rulesHit = new ArrayList<>();

    /** 无异常/数据问题时的说明（STABLE/DATA_ISSUE 分支） */
    private String message;
}
