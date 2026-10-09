package com.agenthub.ai.rcagent.sqlguard.risk;

import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风险分级上下文（阈值来自策略/元数据/配置，禁止代码硬编码判定阈值）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskRuleContext {

    /** 多表大 JOIN 判定阈值（joinCount >= 值且无行级过滤 → HIGH） */
    private int bigJoinCount;

    /** 复杂条件判定阈值（whereCount >= 值 → MEDIUM） */
    private int complexWhereCount;

    /** 大表行数估计阈值（单表无 where/limit 且行数估计 >= 值 → HIGH 全扫风险；<=0 表示无估计不触发） */
    private long bigTableRows;

    /** 是否开启 BLOCK 判定总开关（对应策略 block_enabled；关闭时 DML/DDL/未知表交由 db-access-common 只读层兜底） */
    private boolean blockEnabled;

    /** SELECT * 策略 BLOCK（对应策略 select_star_policy=BLOCK 且 blockEnabled） */
    private boolean selectStarBlock;

    /** 命中 HIGH 敏感字段的表.列摘要（AST 命中，来自 SensitiveFieldRegistry 或合规清单） */
    private java.util.List<String> sensitiveColumns;

    /** 涉及表行数估计（表名大写 -> 估计行数，来自元数据/采样；可空 map） */
    private java.util.Map<String, Long> tableRowEstimate;

    public static RiskRuleContext defaults() {
        return RiskRuleContext.builder()
                .bigJoinCount(3)
                .complexWhereCount(3)
                .bigTableRows(0L)
                .blockEnabled(true)
                .selectStarBlock(true)
                .build();
    }

    public RiskRuleContext withFeatureDefaults(SqlFeature feature) {
        return this;
    }
}
