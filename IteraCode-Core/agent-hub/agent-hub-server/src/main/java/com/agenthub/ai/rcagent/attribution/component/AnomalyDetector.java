package com.agenthub.ai.rcagent.attribution.component;

import com.agenthub.ai.rcagent.attribution.config.AttributionProperties;
import com.agenthub.ai.rcagent.attribution.model.AnomalyResult;
import com.agenthub.ai.rcagent.attribution.model.ConfigOverride;
import com.agenthub.ai.rcagent.attribution.model.QualityFlags;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 异常识别器（AnomalyDetector，FR-001/FR-BE-001）。
 *
 * <p>基于配置阈值（mom/yoy 幅度、最小绝对变化）与总体/基线查询结果计算方向/幅度/严重度，
 * 输出 anomaly_json；区分"真异常"与"口径/数据问题"（配合 QualityIssueDetector）。</p>
 */
@Component
public class AnomalyDetector {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * 依据本期/基期总体值判定是否异常。
     *
     * @param curTotal  本期总体值
     * @param baseTotal 基期总体值
     * @param cfg       生效规则（阈值/最小绝对变化）
     * @param flags     口径/数据质量信号（供伪异常判别）
     */
    public AnomalyResult judge(BigDecimal curTotal, BigDecimal baseTotal,
                               ConfigOverride.EffectiveConfig cfg, QualityFlags flags) {
        AnomalyResult result = new AnomalyResult();
        result.setPseudoFlags(flags == null ? new QualityFlags() : flags);
        BigDecimal cur = curTotal == null ? BigDecimal.ZERO : curTotal;
        BigDecimal base = baseTotal == null ? BigDecimal.ZERO : baseTotal;
        result.setCurTotal(cur);
        result.setBaseTotal(base);

        boolean hasSignal = flags != null && (flags.isCaliberChange() || flags.isDataGap()
                || flags.isEmptyResult() || flags.isSqlBlocked());
        // 无总体值：无法判异常（数据问题信号优先）
        if (hasSignal && base.compareTo(BigDecimal.ZERO) == 0 && cur.compareTo(BigDecimal.ZERO) == 0) {
            result.setAnomaly(false);
            result.setDirection("NONE");
            result.setSeverity("LOW");
            result.setMessage(describeFlags(flags));
            return result;
        }
        BigDecimal delta = cur.subtract(base);
        BigDecimal absDelta = delta.abs();
        // 最小绝对变化门限：变化太小不算异常（BR/FR-001）
        if (absDelta.compareTo(BigDecimal.valueOf(cfg.getMinDeltaAbs())) < 0) {
            result.setAnomaly(false);
            result.setDirection("NONE");
            result.setSeverity("LOW");
            result.setMessage("指标变化幅度未达阈值（min_delta_abs=" + cfg.getMinDeltaAbs() + "），判定平稳");
            return result;
        }
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            // 基期为 0：新出现型异常，方向上探
            result.setAnomaly(true);
            result.setDirection(cur.compareTo(BigDecimal.ZERO) > 0 ? "UP" : "DOWN");
            result.setRateMom(null);
            result.setRulesHit(List.of("BASE_ZERO_NEW_APPEARED"));
            result.setSeverity(severityByRate(null, absDelta, cfg));
            return result;
        }
        BigDecimal rate = delta.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
        String dir = delta.signum() > 0 ? "UP" : "DOWN";
        result.setRateMom(rate);
        result.setDirection(dir);
        List<String> rules = new ArrayList<>();
        double threshold = cfg.getMomThresholdRate();
        double absRate = rate.abs().doubleValue();
        if (absRate >= threshold) {
            result.setAnomaly(true);
            rules.add("MOM_RATE_" + (dir.equals("UP") ? "UP" : "DOWN") + "_GT_" + Math.round(threshold));
        }
        // 严重度
        result.setSeverity(severityByRate(rate, absDelta, cfg));
        result.setRulesHit(rules);
        if (!result.isAnomaly()) {
            result.setMessage("指标环比变化率 " + rate.toPlainString() + "%，未达判定阈值 "
                    + threshold + "%，判定平稳");
        } else if (hasSignal) {
            result.setMessage("存在口径/数据质量信号，需先核查数据可靠性");
        }
        return result;
    }

    private String severityByRate(BigDecimal rate, BigDecimal absDelta, ConfigOverride.EffectiveConfig cfg) {
        double absRate = rate == null ? Double.MAX_VALUE : Math.abs(rate.doubleValue());
        if (absRate >= 50) {
            return "HIGH";
        }
        double threshold = cfg.getMomThresholdRate();
        if (absRate >= threshold) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String describeFlags(QualityFlags flags) {
        List<String> list = flags.activeSignals();
        return list.isEmpty() ? "无法取得总体值" : "信号: " + String.join("、", list);
    }
}
