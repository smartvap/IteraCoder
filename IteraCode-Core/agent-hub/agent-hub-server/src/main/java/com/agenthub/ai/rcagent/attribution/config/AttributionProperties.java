package com.agenthub.ai.rcagent.attribution.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 归因规则配置（{@code agenthub.attribution.*}）。
 *
 * <p>对应 data-model §5 规则配置默认值：anomaly 阈值 / drill 上限 / contribution 分母 / review 开关，
 * 全部可被请求级 config_overrides 覆盖；MVP 配置优先，P3 可视运营改为 DB 化策略。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "agenthub.attribution")
public class AttributionProperties {

    /** 异常识别配置 */
    private Anomaly anomaly = new Anomaly();

    /** 维度下钻配置 */
    private Drill drill = new Drill();

    /** 贡献拆解配置 */
    private Contribution contribution = new Contribution();

    /** 审核开关配置 */
    private Review review = new Review();

    @Data
    public static class Anomaly {

        /** 环比波动判定阈值（%） */
        private Double momThresholdRate = 30.0;

        /** 同比波动判定阈值（%） */
        private Double yoyThresholdRate = 30.0;

        /** 最小绝对变化量（小于则忽略） */
        private Double minDeltaAbs = 10.0;

        /** 趋势连续性阈值（MVP 预留，按 T-3 期符号一致比例） */
        private Double continuityThreshold = 0.6;
    }

    @Data
    public static class Drill {

        /** 单次归因下钻层级上限（BR-004） */
        private Integer maxDepth = 5;

        /** 单次归因生成查询次数上限（BR-004） */
        private Integer maxQueries = 20;

        /** 单层分组返回 topN 段 */
        private Integer topSegments = 20;
    }

    @Data
    public static class Contribution {

        /** 贡献占比分母使用同向总变化（true=按同向贡献和；false=按绝对值总和） */
        private Boolean positiveMajority = true;
    }

    @Data
    public static class Review {

        /** MVP 调试期强制全部归因查询走人工（联调用），默认 false */
        private Boolean mvpReviewAll = false;

        /** 高严重度结论强制人工审核（敏感/口径未 verified 始终强制） */
        private Boolean highReviewRequired = true;

        /**
         * 审核人用户 ID 白名单（接口 3 include_sql=true 受控展示归因 SQL 原文的权限名单）。
         *
         * <p>默认空 = 一律拒绝（fail-closed）；当前项目 JWT 为占位实现（BaseContext.userId 硬编码），
         * dev 联调如需展示 SQL 请在 yml 配置 {@code agenthub.attribution.review.reviewer-user-ids}；
         * 真实 RBAC 落地后应替换为角色/权限点校验，此处仅作最小实现门闸。</p>
         */
        private List<Long> reviewerUserIds = new ArrayList<>();
    }

    // ============ 便捷读取（避免调用处重复判空） ============

    public double momThresholdRateValue() {
        return anomaly == null || anomaly.getMomThresholdRate() == null ? 30.0 : anomaly.getMomThresholdRate();
    }

    public double yoyThresholdRateValue() {
        return anomaly == null || anomaly.getYoyThresholdRate() == null ? 30.0 : anomaly.getYoyThresholdRate();
    }

    public double minDeltaAbsValue() {
        return anomaly == null || anomaly.getMinDeltaAbs() == null ? 10.0 : anomaly.getMinDeltaAbs();
    }

    public double continuityThresholdValue() {
        return anomaly == null || anomaly.getContinuityThreshold() == null ? 0.6 : anomaly.getContinuityThreshold();
    }

    public int maxDepthValue() {
        return drill == null || drill.getMaxDepth() == null ? 5 : Math.min(Math.max(drill.getMaxDepth(), 1), 10);
    }

    public int maxQueriesValue() {
        return drill == null || drill.getMaxQueries() == null ? 20 : Math.min(Math.max(drill.getMaxQueries(), 1), 200);
    }

    public int topSegmentsValue() {
        return drill == null || drill.getTopSegments() == null ? 20
                : Math.min(Math.max(drill.getTopSegments(), 1), 200);
    }

    public boolean positiveMajorityValue() {
        return contribution == null || !Boolean.FALSE.equals(contribution.getPositiveMajority());
    }

    public boolean mvpReviewAllValue() {
        return review != null && Boolean.TRUE.equals(review.getMvpReviewAll());
    }

    public boolean highReviewRequiredValue() {
        return review == null || !Boolean.FALSE.equals(review.getHighReviewRequired());
    }

    /** 审核人用户 ID 白名单（空 = 无人可查看受控 SQL 原文，见 {@link Review#reviewerUserIds}） */
    public List<Long> reviewerUserIds() {
        return review == null || review.getReviewerUserIds() == null
                ? new ArrayList<>() : review.getReviewerUserIds();
    }
}
