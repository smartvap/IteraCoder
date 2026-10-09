package com.agenthub.ai.rcagent.attribution.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

/**
 * 分析时间窗（time_range_json 快照：compare_type/current/base/granularity）。
 */
@Data
public class TimeRange {

    /** 对比方式 MOM/YOY/CUSTOM（time_range_json 顶层 compare_type） */
    @JSONField(name = "compare_type")
    private String compareType;

    /** 本期时间窗 */
    private Range current = new Range();

    /** 基期/对比期时间窗 */
    private Range base = new Range();

    @Data
    public static class Range {

        /** 开始（yyyy-MM-dd） */
        private String start;

        /** 结束（yyyy-MM-dd） */
        private String end;

        /** 粒度 day/month/year 等 */
        private String granularity;
    }
}
