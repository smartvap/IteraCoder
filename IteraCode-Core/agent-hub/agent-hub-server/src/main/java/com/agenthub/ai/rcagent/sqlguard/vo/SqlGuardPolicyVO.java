package com.agenthub.ai.rcagent.sqlguard.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 安全策略视图（HTTP 接口 3 data.records[]）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SqlGuardPolicyVO {

    private Long id;

    private String policyScope;

    private String dsId;

    private String policyName;

    private Integer maxRowsDefault;

    private Integer maxSecondsDefault;

    private String selectStarPolicy;

    private Integer blockEnabled;

    private String sensitivePolicy;

    private String sensitiveSource;

    private Integer lowAutoEnabled;

    private Integer mvpReviewAll;

    private Integer highReviewRequired;

    private Integer repairMaxRounds;

    /** 允许方言数组 */
    private List<String> dialectWhitelist;

    private Integer configVersion;

    private String remark;

    private Date updateTime;
}
