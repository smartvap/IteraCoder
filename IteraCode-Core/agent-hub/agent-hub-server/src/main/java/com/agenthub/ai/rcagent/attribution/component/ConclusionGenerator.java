package com.agenthub.ai.rcagent.attribution.component;

import com.agenthub.ai.rcagent.attribution.constant.FactorStatus;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionFactor;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionQueryLog;
import com.agenthub.ai.rcagent.attribution.model.MetricKnowledgeSnapshot;
import com.agenthub.ai.rcagent.attribution.vo.ConclusionVO;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 结论生成器（ConclusionGenerator，FR-006/FR-BE-006）。
 *
 * <p>结构化主因/次要因素/证据链/置信度组装 + 自然语言摘要（确定性模板，中文）；
 * 结论只输出"数据观测到的贡献因子 + 假设解释"，不断言因果（BR-003）。
 * LLM 润色为可选扩展点：MVP 采用模板化摘要保证离线可用与输入可控（日志/外部输出不泄露 question 与 SQL 原文）。</p>
 */
@Component
public class ConclusionGenerator {

    /** 置信度低阈值（触发人工审核参考） */
    private static final double LOW_CONFIDENCE = 0.6;

    /** 次要因素最多 3 个 */
    private static final int MAX_SECONDARY = 3;

    /** 新/消失段展示上限 */
    private static final int MAX_SEGMENT_ITEM = 10;

    /**
     * 生成结构化结论。
     *
     * @param factors  候选因子（含 CANDIDATE/MAIN/SECONDARY 状态，未剔除）
     * @param logIndex query_id → 归因查询日志（证据链摘要来源）
     */
    public ConclusionVO generate(GenerateContext ctx) {
        ConclusionVO vo = new ConclusionVO();
        vo.setMetric(ctx.metric);
        vo.setDirection(ctx.direction);
        vo.setCurTotal(ctx.curTotal);
        vo.setBaseTotal(ctx.baseTotal);
        vo.setDelta(ctx.delta == null && ctx.curTotal != null && ctx.baseTotal != null
                ? ctx.curTotal.subtract(ctx.baseTotal) : ctx.delta);
        if (ctx.factors == null || ctx.factors.isEmpty()) {
            return vo;
        }
        List<RcagentAttributionFactor> candidates = ctx.factors.stream()
                .filter(f -> f != null && !FactorStatus.REJECTED.name().equals(f.getStatus()))
                .sorted(Comparator.comparing(f -> f.getContribution() == null
                        ? BigDecimal.ZERO : f.getContribution().abs(), Comparator.reverseOrder()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return vo;
        }

        RcagentAttributionFactor main = candidates.get(0);
        vo.setMainFactor(toBrief(main));
        List<RcagentAttributionFactor> secondaries = candidates.size() > 1
                ? candidates.subList(1, Math.min(candidates.size(), MAX_SECONDARY + 1))
                : new ArrayList<>();
        for (RcagentAttributionFactor s : secondaries) {
            vo.getSecondaryFactors().add(toBrief(s));
        }
        collectSegments(candidates, vo);

        // 证据链（main + secondary 证据 query）
        List<RcagentAttributionFactor> chainFactors = new ArrayList<>();
        chainFactors.add(main);
        chainFactors.addAll(secondaries);
        for (RcagentAttributionFactor f : chainFactors) {
            appendEvidenceChain(vo, f, ctx.logIndex);
        }

        // 置信度：口径可信度 × 证据完整 × 主因覆盖率；口径未 verified 封顶 0.3
        BigDecimal confidence = computeConfidence(main, ctx.snapshot, ctx.factors);
        vo.setConfidence(confidence);
        if (ctx.sensitive) {
            vo.setSensitiveNote("结论命中敏感归因，仅供授权人员审阅，已强制人工确认");
        }
        vo.setActionSuggestions(buildSuggestions(main));
        return vo;
    }

    /** 模板化自然语言摘要（中文，不泄露 SQL/question 原文） */
    public String summary(ConclusionVO vo) {
        if (vo == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (vo.getMetric() != null) {
            sb.append(vo.getMetric());
        } else {
            sb.append("指标");
        }
        boolean hasTotal = vo.getCurTotal() != null && vo.getBaseTotal() != null;
        if (hasTotal) {
            sb.append("本期").append(scalePlain(vo.getCurTotal())).append("，基期")
                    .append(scalePlain(vo.getBaseTotal()));
        }
        if (vo.getDelta() != null && vo.getDirection() != null) {
            sb.append("，").append("UP".equals(vo.getDirection()) ? "上升" : "下降")
                    .append(scalePlain(vo.getDelta().abs()));
        }
        ConclusionVO.FactorBrief main = vo.getMainFactor();
        if (main != null && main.getSegmentValue() != null) {
            sb.append("。主因：").append(main.getDimensionLabel() == null ? main.getDimension() : main.getDimensionLabel())
                    .append("「").append(main.getSegmentValue()).append("」");
            if (main.getContributionRatio() != null) {
                sb.append("（贡献占比 ").append(main.getContributionRatio().stripTrailingZeros().toPlainString()).append("%）");
            }
            if (main.getHypothesis() != null) {
                sb.append("，").append(main.getHypothesis());
            }
        }
        if (!vo.getSecondaryFactors().isEmpty()) {
            List<String> sec = new ArrayList<>();
            for (ConclusionVO.FactorBrief s : vo.getSecondaryFactors()) {
                if (s == null || s.getSegmentValue() == null) {
                    continue;
                }
                String label = s.getDimensionLabel() == null ? s.getDimension() : s.getDimensionLabel();
                sec.add(label + "「" + s.getSegmentValue() + "」"
                        + (s.getContributionRatio() == null ? "" : "占" + s.getContributionRatio().stripTrailingZeros().toPlainString() + "%"));
            }
            if (!sec.isEmpty()) {
                sb.append("；次要因素：").append(String.join("、", sec));
            }
        }
        if (vo.getSensitiveNote() != null) {
            sb.append("。").append(vo.getSensitiveNote());
        }
        return sb.toString();
    }

    // ============ 内部组装 ============

    private ConclusionVO.FactorBrief toBrief(RcagentAttributionFactor f) {
        ConclusionVO.FactorBrief brief = new ConclusionVO.FactorBrief();
        brief.setFactorId(f.getFactorId());
        brief.setDimension(f.getDimension());
        brief.setDimensionLabel(f.getDimensionLabel());
        brief.setSegmentKey(f.getSegmentKey());
        brief.setSegmentValue(f.getSegmentValue());
        brief.setDeltaValue(f.getDeltaValue());
        brief.setContributionRatio(f.getContributionRatio());
        brief.setHypothesis(f.getHypothesis());
        return brief;
    }

    private void collectSegments(List<RcagentAttributionFactor> candidates, ConclusionVO vo) {
        List<String> news = new ArrayList<>();
        List<String> disappeared = new ArrayList<>();
        for (RcagentAttributionFactor f : candidates) {
            String type = f.getFactorType();
            if (type == null) {
                continue;
            }
            if (type.startsWith("NEW_SEGMENT") && f.getSegmentValue() != null) {
                news.add(f.getSegmentValue());
            } else if (type.startsWith("DISAPPEARED_SEGMENT") && f.getSegmentValue() != null) {
                disappeared.add(f.getSegmentValue());
            }
        }
        vo.setNewSegments(news.size() > MAX_SEGMENT_ITEM ? new ArrayList<>(news.subList(0, MAX_SEGMENT_ITEM)) : news);
        vo.setDisappearedSegments(disappeared.size() > MAX_SEGMENT_ITEM
                ? new ArrayList<>(disappeared.subList(0, MAX_SEGMENT_ITEM)) : disappeared);
    }

    private void appendEvidenceChain(ConclusionVO vo, RcagentAttributionFactor factor,
                                     Map<String, RcagentAttributionQueryLog> logIndex) {
        if (factor == null || factor.getEvidenceQueryIds() == null || logIndex == null) {
            return;
        }
        java.util.List<String> queryIds = parseStringList(factor.getEvidenceQueryIds());
        com.agenthub.ai.rcagent.attribution.vo.EvidenceChainItem previous = null;
        for (String queryId : queryIds) {
            if (queryId == null) {
                continue;
            }
            RcagentAttributionQueryLog log = logIndex.get(queryId);
            if (log == null) {
                continue;
            }
            com.agenthub.ai.rcagent.attribution.vo.EvidenceChainItem item = new com.agenthub.ai.rcagent.attribution.vo.EvidenceChainItem();
            item.setQueryId(log.getQueryId());
            item.setQueryType(log.getQueryType());
            item.setSqlAbstract(log.getSqlAbstract());
            item.setDimension(factor.getDimension());
            // 简单去重（同 factor 的两个 query 只保留 group 类型可读性最好的）
            if (previous != null && previous.getQueryType() != null && item.getQueryType() != null
                    && previous.getQueryType().equals(item.getQueryType())
                    && previous.getSqlAbstract() != null && previous.getSqlAbstract().equals(item.getSqlAbstract())) {
                continue;
            }
            previous = item;
            vo.getEvidenceChain().add(item);
        }
    }

    private BigDecimal computeConfidence(RcagentAttributionFactor main,
                                         MetricKnowledgeSnapshot snapshot,
                                         List<RcagentAttributionFactor> factors) {
        double base = 0.8;
        if (snapshot != null && snapshot.getConfidence() != null) {
            base = snapshot.getConfidence().doubleValue();
        }
        if (snapshot == null || !Integer.valueOf(1).equals(snapshot.getVerified())) {
            base = Math.min(base, 0.3);
        }
        boolean hasEvidence = main != null && main.getEvidenceQueryIds() != null && !main.getEvidenceQueryIds().isEmpty();
        double evidenceFactor = hasEvidence ? 1.0 : 0.7;
        double mainCoverageFactor = 0.7;
        if (main != null && main.getContributionRatio() != null) {
            double ratio = Math.min(1.0, Math.max(0.0, main.getContributionRatio().doubleValue() / 100.0));
            mainCoverageFactor = 0.6 + 0.4 * ratio;
        }
        double confidence = base * evidenceFactor * mainCoverageFactor;
        confidence = Math.min(1.0, Math.max(0.0, confidence));
        return BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP);
    }

    private List<String> buildSuggestions(RcagentAttributionFactor main) {
        List<String> suggestions = new ArrayList<>();
        if (main == null || main.getSegmentValue() == null) {
            suggestions.add("建议结合业务口径与实际数据进一步排查波动原因");
            return suggestions;
        }
        String label = main.getDimensionLabel() == null ? main.getDimension() : main.getDimensionLabel();
        suggestions.add("优先核查" + label + "「" + main.getSegmentValue() + "」的近期业务动作与数据录入情况");
        if (main.getDeltaValue() != null && main.getDeltaValue().signum() > 0) {
            suggestions.add("针对增长子群补充运营/渠道/时间维度的二次下钻验证");
        } else {
            suggestions.add("针对下降子群核查口径/数据源接入是否一致");
        }
        suggestions.add("本结论为数据观测假设，需人工核实后作为行动依据");
        return suggestions;
    }

    /** 数值去尾零展示（避免 1560.0000 等冗长） */
    private String scalePlain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private java.util.List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return com.alibaba.fastjson2.JSON.parseArray(json, String.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** 生成上下文（attribution service 组装） */
    public static class GenerateContext {

        private final String metric;
        private final String direction;
        private final BigDecimal curTotal;
        private final BigDecimal baseTotal;
        private final BigDecimal delta;
        private final List<RcagentAttributionFactor> factors;
        private final MetricKnowledgeSnapshot snapshot;
        private final Map<String, RcagentAttributionQueryLog> logIndex;
        private final boolean sensitive;

        public GenerateContext(String metric, String direction, BigDecimal curTotal, BigDecimal baseTotal,
                               BigDecimal delta, List<RcagentAttributionFactor> factors,
                               MetricKnowledgeSnapshot snapshot,
                               Map<String, RcagentAttributionQueryLog> logIndex, boolean sensitive) {
            this.metric = metric;
            this.direction = direction;
            this.curTotal = curTotal;
            this.baseTotal = baseTotal;
            this.delta = delta;
            this.factors = factors;
            this.snapshot = snapshot;
            this.logIndex = logIndex;
            this.sensitive = sensitive;
        }
    }
}
