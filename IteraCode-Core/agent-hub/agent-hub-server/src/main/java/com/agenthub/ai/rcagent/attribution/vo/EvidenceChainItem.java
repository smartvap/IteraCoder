package com.agenthub.ai.rcagent.attribution.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 证据链项（api-contract data-model §4.5 EvidenceChainItem）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class EvidenceChainItem {

    /** 证据查询 id（关联 rcagent_attribution_query_log.query_id） */
    private String queryId;

    /** 查询类型（GROUP_CUR/GROUP_BASE/CROSS_GROUP…） */
    private String queryType;

    /** SQL 脱敏抽象 */
    private String sqlAbstract;

    /** 支撑维度 */
    private String dimension;
}
