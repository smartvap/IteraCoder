package com.agenthub.ai.rcagent.attribution.vo;

import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.util.Date;
import java.util.List;

/**
 * 下钻节点视图（api-contract AttributionNodeVO，data-model rcagent_attribution_node）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionNodeVO {

    private String nodeId;

    private String taskId;

    private Integer layer;

    private String dimension;

    private String dimensionLabel;

    private String nodeType;

    private String parentNodeId;

    private String dimensionValue;

    private String drillStatus;

    private Integer sortOrder;

    /** 本层对比摘要（反序列化 compare_summary_json） */
    private DrillNodeSummaryVO compareSummary;

    /** 证据查询 id 列表 */
    private List<String> queryIds;

    /** 因子 id 列表 */
    private List<String> factorIds;

    /** 本层 top 因子摘要 */
    private List<AttributionFactorVO> factors;

    private Date createTime;

    public static AttributionNodeVO from(RcagentAttributionNode node) {
        AttributionNodeVO vo = new AttributionNodeVO();
        if (node != null) {
            BeanUtils.copyProperties(node, vo);
        }
        return vo;
    }
}
