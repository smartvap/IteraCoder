package com.agenthub.ai.rcagent.sqlguard.model;

import com.agenthub.ai.dbaccess.model.QueryResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分级保护只读执行结果（SPI S-5 出参 GuardedQueryResult，data-model §7）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardedQueryResult {

    /** 分级结论 */
    private RiskAssessment risk;

    /** 五级报告（L4/L5 已执行后补全） */
    private ValidationReport report;

    /** 是否实际执行 */
    private Boolean executed;

    /** 执行结果（db-access-common QueryResult，仅 executed=true 时有值） */
    private QueryResult result;
}
