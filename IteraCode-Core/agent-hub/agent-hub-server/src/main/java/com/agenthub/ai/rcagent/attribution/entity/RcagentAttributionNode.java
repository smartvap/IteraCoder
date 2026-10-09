package com.agenthub.ai.rcagent.attribution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 下钻节点（rcagent_attribution_node）。
 *
 * <p>一次"在某维度下钻并完成贡献拆解"的层节点结果（FR-002/003）；
 * compare_summary_json/query_ids/factor_ids 为 JSON 文本列。</p>
 */
@Data
@TableName(value = "rcagent_attribution_node")
public class RcagentAttributionNode {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 下钻节点业务 ID（attrnode_ + task_id + 序号） */
    private String nodeId;

    /** 归属归因任务 */
    private String taskId;

    /** 下钻层级（1=首层维度下钻；ROOT 层=0） */
    private Integer layer;

    /** 下钻维度（BRANCH/TIME_SLOT/BIZ_TYPE/CHANNEL 或自定义 schema 字段） */
    private String dimension;

    /** 维度展示名 */
    private String dimensionLabel;

    /** 节点类型：ROOT/GROUP_DRILL/CROSS_DRILL/QUALITY_CHECK */
    private String nodeType;

    /** 上一层节点（多维交叉/追问时使用） */
    private String parentNodeId;

    /** 本层下钻所聚焦的子群值 */
    private String dimensionValue;

    /** 本层对比摘要 JSON */
    private String compareSummaryJson;

    /** 证据查询 id 数组 JSON */
    private String queryIds;

    /** 候选因子 id 数组 JSON */
    private String factorIds;

    /** 节点执行状态：EXECUTED/SKIPPED/REVIEWING/FAILED */
    private String drillStatus;

    /** 展示顺序 */
    private Integer sortOrder;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 逻辑删除标记 */
    private Integer isDeleted;
}
