package com.agenthub.ai.rcagent.attribution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 候选因子（rcagent_attribution_factor）。
 *
 * <p>贡献拆解产物（FR-003）：每个子群为一候选因子，带贡献量/贡献占比/变化率/新消失段检测；
 * hypothesis 为假设解释（BR-003 非因果断言）。</p>
 */
@Data
@TableName(value = "rcagent_attribution_factor")
public class RcagentAttributionFactor {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 因子业务 ID（attrf_ + 时间戳 + 序号） */
    private String factorId;

    /** 归属归因任务 */
    private String taskId;

    /** 归属下钻节点 */
    private String nodeId;

    /** 因子类型（FactorType） */
    private String factorType;

    /** 维度名 */
    private String dimension;

    /** 维度展示名 */
    private String dimensionLabel;

    /** 子群键（维度的取值，如营业厅编码/时段 bucket） */
    private String segmentKey;

    /** 子群展示值 */
    private String segmentValue;

    /** 本期子群值 */
    private BigDecimal curValue;

    /** 基期子群值 */
    private BigDecimal baseValue;

    /** 子群变化量（cur - base） */
    private BigDecimal deltaValue;

    /** 子群变化率 % */
    private BigDecimal deltaRatio;

    /** 对指标总变化的贡献量 */
    private BigDecimal contribution;

    /** 子群贡献占比 % */
    private BigDecimal contributionRatio;

    /** 本期占比 % */
    private BigDecimal curShare;

    /** 基期占比 % */
    private BigDecimal baseShare;

    /** 变化类型（SegmentChange） */
    private String segmentChange;

    /** 是否主因（0/1） */
    private Integer isMain;

    /** 按贡献度/显著度排序位 */
    private Integer rank;

    /** 因子置信度（0~1） */
    private BigDecimal confidence;

    /** 假设解释（非因果断言） */
    private String hypothesis;

    /** 支撑证据查询 id 数组 JSON */
    private String evidenceQueryIds;

    /** 是否敏感因子（0/1） */
    private Integer sensitiveFlag;

    /** 因子状态（FactorStatus） */
    private String status;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 逻辑删除标记 */
    private Integer isDeleted;
}
