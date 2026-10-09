package com.agenthub.ai.rcagent.retrieval.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 融合检索质量指标聚合 VO（接口3 data：overview + daily）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class RetrievalStatsVO {

    /** 总览指标 */
    private RetrievalStatsOverviewVO overview = new RetrievalStatsOverviewVO();

    /** 按日/周聚合明细 */
    private List<RetrievalDailyStatVO> daily = new ArrayList<>();
}
