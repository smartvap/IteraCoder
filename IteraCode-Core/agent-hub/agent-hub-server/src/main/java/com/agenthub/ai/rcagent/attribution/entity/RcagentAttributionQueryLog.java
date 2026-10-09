package com.agenthub.ai.rcagent.attribution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

/**
 * 归因查询日志（rcagent_attribution_query_log）。
 *
 * <p>归因生成的每个 SQL（基线/趋势/分组下钻/交叉/质量探针）的完整审计记录（FR-004/FR-007）。
 * sql_text 属受控内部存储（仅审核展示/结果复现），日志与外部输出一律使用 sql_abstract（AGENTS.md 5.x）。</p>
 */
@Data
@TableName(value = "rcagent_attribution_query_log")
public class RcagentAttributionQueryLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归因查询业务 ID（attrq_ + 时间戳 + 序号） */
    private String queryId;

    /** 数据源实例 ID */
    private String dsId;

    /** 归属归因任务 */
    private String taskId;

    /** 归属下钻节点 */
    private String nodeId;

    /** 查询类型（QueryType） */
    private String queryType;

    /** 归因 SQL 原文（受控存储：仅供审核展示/结果复现） */
    private String sqlText;

    /** SQL 脱敏抽象（表名+字段名+占位符化 WHERE 值） */
    private String sqlAbstract;

    /** 涉及表（JSON 数组） */
    private String involvedTables;

    /** 风险分级：BLOCK/LOW/MEDIUM/HIGH（来自 module-006） */
    private String riskLevel;

    /** 判定：ALLOW/REVIEW/BLOCK（来自 module-006） */
    private String verdict;

    /** 人工审核状态（QueryReviewStatus）：NONE/APPROVED/REJECTED */
    private String reviewStatus;

    /** 是否已只读执行（0/1） */
    private Integer executed;

    /** 执行结果快照 JSON */
    private String resultJson;

    /** L5 结果合理性信号 JSON */
    private String resultCheckJson;

    /** 验证失败/回退时的修复建议 */
    private String fixSuggestion;

    /** 执行/验证失败错误码 */
    private String errCode;

    /** 失败信息摘要（禁止 SQL 原文/连接串/凭据） */
    private String errMsg;

    /** 执行耗时 ms */
    private Integer costMs;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;

    /** 逻辑删除标记 */
    private Integer isDeleted;
}
