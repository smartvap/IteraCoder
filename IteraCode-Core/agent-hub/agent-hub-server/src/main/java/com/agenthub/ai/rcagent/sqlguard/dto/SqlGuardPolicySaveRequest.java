package com.agenthub.ai.rcagent.sqlguard.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * 保存安全策略请求（HTTP 接口 2 POST /api/v1/sqlguard/policy/save）。
 *
 * <p>字段为 snake_case JSON（对齐契约）；除 policy_scope/ds_id 外均可空（空=不更新）。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SqlGuardPolicySaveRequest {

    /** GLOBAL / DS */
    private String policyScope;

    /** DS 行必填 */
    private String dsId;

    /** 策略名 */
    private String policyName;

    /** 行数默认（1~50000） */
    private Integer maxRowsDefault;

    /** 秒数默认（1~60） */
    private Integer maxSecondsDefault;

    /** SELECT * 策略 BLOCK/ALLOW */
    private String selectStarPolicy;

    /** BLOCK 开关 0/1 */
    private Integer blockEnabled;

    /** 敏感策略 MASK/DENY/ALLOW_BY_PERMISSION */
    private String sensitivePolicy;

    /** 敏感清单来源 REGISTRY/COMPLIANCE */
    private String sensitiveSource;

    /** LOW 自动 0/1 */
    private Integer lowAutoEnabled;

    /** MVP 全量人工 0/1 */
    private Integer mvpReviewAll;

    /** HIGH 强制人工 0/1 */
    private Integer highReviewRequired;

    /** 修复轮数 1~10 */
    private Integer repairMaxRounds;

    /** 允许方言数组 */
    private List<String> dialectWhitelist;

    /** 备注 */
    private String remark;
}
