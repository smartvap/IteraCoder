package com.agenthub.ai.rcagent.attribution.model;

import com.agenthub.ai.dbaccess.model.QueryResult;
import lombok.Data;

/**
 * 归因查询校验/执行结构化结果（GuardAdapter 出参，内部串联 + query_log 落库载体）。
 *
 * <p>module-006 BLOCK 或策略未放行不抛异常，而是以 blocked/executed=false 结构化返回（BR-002）。</p>
 */
@Data
public class QueryOutcome {

    /** 归因查询业务 ID */
    private String queryId;

    /** 查询类型（QueryType） */
    private String queryType;

    /** 归属下钻节点 */
    private String nodeId;

    /** 数据源 */
    private String dsId;

    /** 归因 SQL 原文（仅入受控存储，禁止日志/外部输出） */
    private String sqlText;

    /** SQL 脱敏抽象 */
    private String sqlAbstract;

    /** 风险分级：BLOCK/LOW/MEDIUM/HIGH（来自 module-006） */
    private String riskLevel;

    /** 判定：ALLOW/REVIEW/BLOCK */
    private String verdict;

    /** 人工审核状态：NONE/APPROVED/REJECTED */
    private String reviewStatus;

    /** 是否已只读执行 */
    private boolean executed;

    /** module-006 执行结果（仅 executed=true） */
    private QueryResult result;

    /** 是否被 BLOCK/未放行（结构化返回给调用方决策） */
    private boolean blocked;

    /** BLOCK/失败原因摘要（不含 SQL 原文） */
    private String blockReason;

    /** L5 结果检查信号（quality_flags_json 输入） */
    private String resultCheckJson;

    /** 修复建议（module-006 fixSuggestions 摘要） */
    private String fixSuggestion;

    /** 失败错误码摘要 */
    private String errCode;

    /** 失败信息摘要（禁止 SQL 原文/连接串/凭据） */
    private String errMsg;

    /** 执行耗时 ms */
    private Integer costMs;

    /** 需要人工放行（MED/HIGH）时在审核通过后由 decideReview 复跑并置 true */
    private boolean requireManualApproval;

    public boolean isAllAllowed() {
        return !blocked && executed;
    }
}
