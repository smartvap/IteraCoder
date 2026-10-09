package com.agenthub.ai.rcagent.feedback.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 反馈运营统计 VO（接口4 data）。
 *
 * <p>typeDistribution/statusDistribution 为契约约定的 Map 聚合（feedbackType/status → 数量）；
 * writebackCount 为 A/B 库沉淀统计（fb_feedback_flow + fb_wiki_writeback 可量化部分）。</p>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FeedbackStatsVO {

    /** 反馈总数 */
    private Long total;

    /** feedbackType → 数量 */
    private Map<String, Long> typeDistribution;

    /** status → 数量 */
    private Map<String, Long> statusDistribution;

    /** 待审核数（REVIEWING） */
    private Long pendingReview;

    /** 闭环率（CLOSED/total，0~1） */
    private BigDecimal closedRate;

    /** A/B 沉淀统计 */
    private WritebackStatVO writebackCount;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WritebackStatVO {
        /** 完成库 A 写回的闭环流数（fb_feedback_flow.a_writeback_done=1） */
        private Long aObjects;

        /** B 库出站任务总数（fb_wiki_writeback） */
        private Long bPages;

        /** B 库同步成功数 */
        private Long wikiSuccess;

        /** B 库同步失败数 */
        private Long wikiFailed;
    }
}
