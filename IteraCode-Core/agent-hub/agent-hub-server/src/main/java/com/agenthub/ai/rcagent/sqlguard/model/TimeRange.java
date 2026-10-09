package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 时间范围意图（SqlPlanView.timeRange）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TimeRange {

    /** 起始时间（ISO 文本或自然语言摘要） */
    private String start;

    /** 结束时间（ISO 文本或自然语言摘要） */
    private String end;
}
