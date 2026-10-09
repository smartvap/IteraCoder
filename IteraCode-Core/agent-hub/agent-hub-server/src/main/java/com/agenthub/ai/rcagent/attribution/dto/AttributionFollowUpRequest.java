package com.agenthub.ai.rcagent.attribution.dto;

import com.agenthub.ai.rcagent.attribution.model.ConfigOverride;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

import java.util.List;

/**
 * 追问下钻入参（POST /api/v1/attribution/follow-up，api-contract 接口 2，FR-008）。
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class AttributionFollowUpRequest {

    /** 既有归因任务（追问链，必填） */
    private String taskId;

    /** 本次追问内容 */
    private String question;

    /** 聚焦因子（在该子群上继续下钻） */
    private FocusFactor focusFactor;

    /** 本轮候选维度（为空自动推断） */
    private List<String> dimensions;

    /** 是否重置已下钻路径（true=从首层重来） */
    private Boolean resetPath;

    /** 规则覆盖（同 analyze） */
    private ConfigOverride configOverrides;

    /** 操作人 */
    private Long operatorId;

    /** 聚焦因子：dimension + segment_key/segment_value */
    @Data
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class FocusFactor {

        /** 维度（如 BRANCH） */
        private String dimension;

        /** 子群键（如 B0103） */
        private String segmentKey;

        /** 子群展示值（如 西城营业厅） */
        private String segmentValue;
    }
}
