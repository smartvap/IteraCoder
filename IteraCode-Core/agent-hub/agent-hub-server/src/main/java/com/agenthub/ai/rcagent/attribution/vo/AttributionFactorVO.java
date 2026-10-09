package com.agenthub.ai.rcagent.attribution.vo;

import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionFactor;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 候选因子视图（api-contract AttributionFactorVO，data-model rcagent_attribution_factor）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionFactorVO {

    private String factorId;

    private String taskId;

    private String nodeId;

    private String factorType;

    private String dimension;

    private String dimensionLabel;

    private String segmentKey;

    private String segmentValue;

    private BigDecimal curValue;

    private BigDecimal baseValue;

    private BigDecimal deltaValue;

    private BigDecimal deltaRatio;

    private BigDecimal contribution;

    private BigDecimal contributionRatio;

    private BigDecimal curShare;

    private BigDecimal baseShare;

    private String segmentChange;

    private Integer isMain;

    private Integer rank;

    private BigDecimal confidence;

    private String hypothesis;

    private List<String> evidenceQueryIds;

    private Integer sensitiveFlag;

    private String status;

    private Date createTime;

    public static AttributionFactorVO from(RcagentAttributionFactor factor) {
        AttributionFactorVO vo = new AttributionFactorVO();
        if (factor != null) {
            BeanUtils.copyProperties(factor, vo);
        }
        return vo;
    }
}
