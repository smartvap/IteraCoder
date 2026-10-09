package com.agenthub.ai.rcagent.kb.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * 采样统计（脱敏后）：row_estimate/cardinality/last_ddl_time。
 * 存库时整对象 JSON 序列化为 kb_*.sample_stats TEXT 列。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class KbSampleStats {

    /** 行数估算 */
    @JSONField(name = "row_estimate")
    private Long rowEstimate;

    /** 基数估算 */
    @JSONField(name = "cardinality")
    private Long cardinality;

    /** 最近 DDL 时间 */
    @JSONField(name = "last_ddl_time")
    private String lastDdlTime;
}
