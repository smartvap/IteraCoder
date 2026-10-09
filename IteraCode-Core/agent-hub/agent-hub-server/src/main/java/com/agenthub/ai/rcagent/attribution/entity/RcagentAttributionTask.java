package com.agenthub.ai.rcagent.attribution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 归因任务主表（rcagent_attribution_task）。
 *
 * <p>单次归因运行根记录：FR-001 异常识别 → FR-002/003 下钻拆解 → FR-004 归因查询 →
 * FR-006 结论 → FR-007 审核；JSON 复合列（口径快照/时间窗/异常信号/下钻路径/结论）存 TEXT。</p>
 */
@Data
@TableName(value = "rcagent_attribution_task")
public class RcagentAttributionTask {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归因任务业务 ID（attr_ + yyyyMMddHHmmss + 6 位随机） */
    private String taskId;

    /** 关联工作流会话 thread_id */
    private String threadId;

    /** 追问链父任务 task_id */
    private String parentTaskId;

    /** 数据源实例 ID（BR-001/004 隔离维度） */
    private String dsId;

    /** 指标名 */
    private String metricName;

    /** 命中 kb_metric.id（无 verified 口径为 NULL） */
    private Long kbMetricId;

    /** 是否有 verified/active 口径定义（0=无，归因降级/强制人工） */
    private Integer metricDefinitionVerified;

    /** 指标口径快照 JSON（BR-001 冻结） */
    private String metricKnowledgeJson;

    /** 触发方式：METRIC_ANOMALY / USER_FOLLOW_UP */
    private String triggerType;

    /** 用户原始追问/主问题（日志与外部输出截断，AGENTS.md 5.3） */
    private String question;

    /** 追问上下文 JSON */
    private String queryContextJson;

    /** 分析时间窗 JSON */
    private String timeRangeJson;

    /** 对比方式：MOM / YOY / CUSTOM */
    private String compareType;

    /** 任务状态机（AttributionTaskStatus） */
    private String status;

    /** 审核阶段（ReviewState）：NONE/QUERY_REVIEWING/CONCLUSION_REVIEWING */
    private String reviewState;

    /** 异常识别结果快照 JSON */
    private String anomalyJson;

    /** 已下钻路径 JSON 数组 */
    private String drillPathJson;

    /** 当前下钻深度 */
    private Integer drillDepth;

    /** 已生成归因查询次数（BR-004） */
    private Integer queryCount;

    /** 口径/数据质量信号 JSON（FR-005） */
    private String qualityFlagsJson;

    /** 结构化结论 JSON */
    private String conclusionJson;

    /** 自然语言结论摘要 */
    private String conclusionSummary;

    /** 归因结论置信度（0~1） */
    private BigDecimal confidence;

    /** 结果状态（ResultStatus） */
    private String resultStatus;

    /** 是否命中敏感归因（0/1，BR-003 强制人工） */
    private Integer sensitiveFlag;

    /** 失败信息（禁止 SQL 原文/连接串/凭据） */
    private String errorMsg;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 逻辑删除标记 */
    private Integer isDeleted;
}
