package com.agenthub.ai.rcagent.attribution.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 口径/数据质量信号（quality_flags_json / anomaly_json.pseudo_flags，FR-005）。
 *
 * <p>用于区分"业务真异常 vs 口径/数据问题"，避免假归因。</p>
 */
@Data
public class QualityFlags {

    /** 口径版本/口径定义是否在归因期间发生变化 */
    @JSONField(name = "caliber_change")
    private boolean caliberChange;

    /** 数据断层/缺数（分组基期本期段缺失或时间断层） */
    @JSONField(name = "data_gap")
    private boolean dataGap;

    /** 空结果（分组/总体查询无行） */
    @JSONField(name = "empty_result")
    private boolean emptyResult;

    /** JOIN 膨胀（module-006 L5 回传） */
    @JSONField(name = "join_inflation")
    private boolean joinInflation;

    /** 归因 SQL 被 BLOCK（口径/表映射检查信号） */
    @JSONField(name = "sql_blocked")
    private boolean sqlBlocked;

    /** 信号原因描述（截断） */
    private String reason;

    public List<String> activeSignals() {
        List<String> signals = new ArrayList<>();
        if (caliberChange) {
            signals.add("口径版本变更");
        }
        if (dataGap) {
            signals.add("数据断层");
        }
        if (emptyResult) {
            signals.add("空结果");
        }
        if (joinInflation) {
            signals.add("JOIN膨胀");
        }
        if (sqlBlocked) {
            signals.add("SQL被安全拦截");
        }
        return signals;
    }
}
