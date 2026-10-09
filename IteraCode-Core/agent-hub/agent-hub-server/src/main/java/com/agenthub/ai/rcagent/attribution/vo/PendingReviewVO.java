package com.agenthub.ai.rcagent.attribution.vo;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 待审项（api-contract data.pending_review）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PendingReviewVO {

    /** 进入审核原因（MEDIUM/HIGH 归因查询/敏感因子/口径未verified/低置信） */
    private String reason;

    /** 待审项明细 */
    private List<PendingReviewItem> items = new ArrayList<>();

    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class PendingReviewItem {

        /** QUERY / CONCLUSION */
        private String type;

        /** task_id */
        private String taskId;

        /** QUERY 时 query_id；CONCLUSION 时为 task_id */
        private String queryId;

        /** QUERY 时查询类型 */
        private String queryType;

        /** SQL 脱敏抽象 */
        private String sqlAbstract;

        /** 风险等级（QUERY） */
        private String riskLevel;

        /** 原因（如 JOIN_MEDIUM / SENSITIVE / NOT_VERIFIED / LOW_CONFIDENCE） */
        private String reason;

        /** CONCLUSION 时结论摘要 */
        private String conclusionSummary;
    }
}
