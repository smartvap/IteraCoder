package com.agenthub.ai.rcagent.attribution.vo;

import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionFactor;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionNode;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionQueryLog;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionReviewLog;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionTask;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 归因任务视图（api-contract AttributionTaskVO，data-model rcagent_attribution_task）。
 *
 * <p>分页列表不含大 JSON 详情（conclusion_json/metric_knowledge_json/anomaly_json/drill_path_json），
 * 摘要含 summary/confidence。</p>
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionTaskVO {

    private String taskId;

    private String threadId;

    private String parentTaskId;

    private String dsId;

    private String metricName;

    private Long kbMetricId;

    private Integer metricDefinitionVerified;

    private String triggerType;

    private String compareType;

    private String status;

    private String reviewState;

    private Integer drillDepth;

    private Integer queryCount;

    private Integer sensitiveFlag;

    private String resultStatus;

    private BigDecimal confidence;

    private String conclusionSummary;

    private String errorMsg;

    private Date createTime;

    private Date updateTime;

    /** 是否已解析出异常方向（摘要字段，便于看板展示，null 表示未判定） */
    private String anomalyDirection;

    /** 因子汇总（详情接口/结论时填充） */
    private List<AttributionFactorVO> factors;

    /** 查询汇总（详情接口/审核展示时填充） */
    private List<AttributionQueryVO> queries;

    public static AttributionTaskVO from(RcagentAttributionTask task) {
        AttributionTaskVO vo = new AttributionTaskVO();
        if (task != null) {
            BeanUtils.copyProperties(task, vo);
        }
        return vo;
    }

    public static AttributionTaskVO from(RcagentAttributionTask task, String anomalyDirection) {
        AttributionTaskVO vo = from(task);
        vo.setAnomalyDirection(anomalyDirection);
        return vo;
    }
}
