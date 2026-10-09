package com.agenthub.ai.rcagent.attribution.vo;

import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionQueryLog;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.util.Date;

/**
 * 归因查询审计视图（api-contract AttributionQueryVO，data-model rcagent_attribution_query_log）。
 *
 * <p>sql_text 属受控字段：仅审核展示（有审核权限 / include_sql=true）填充，
 * 普通视图只返回 sql_abstract（AGENTS.md 5.x）。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionQueryVO {

    private String queryId;

    private String dsId;

    private String taskId;

    private String nodeId;

    private String queryType;

    /** 受控字段：SQL 原文（缺省 null，仅审核展示场景填充） */
    private String sqlText;

    private String sqlAbstract;

    private String involvedTables;

    private String riskLevel;

    private String verdict;

    private String reviewStatus;

    private Integer executed;

    private String resultJson;

    private String resultCheckJson;

    private String fixSuggestion;

    private String errCode;

    private String errMsg;

    private Integer costMs;

    private Date createTime;

    public static AttributionQueryVO from(RcagentAttributionQueryLog entity) {
        AttributionQueryVO vo = new AttributionQueryVO();
        if (entity != null) {
            BeanUtils.copyProperties(entity, vo);
        }
        return vo;
    }
}
