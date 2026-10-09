package com.agenthub.ai.rcagent.attribution.component;

import com.agenthub.ai.rcagent.attribution.model.MetricKnowledgeSnapshot;
import com.agenthub.ai.rcagent.attribution.model.QualityFlags;
import com.agenthub.ai.rcagent.attribution.model.QueryOutcome;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 口径/数据质量排查（QualityIssueDetector，FR-005/FR-BE-005）。
 *
 * <p>识别 kb_metric 版本/口径变更信号 + module-006 L5 Result 校验信号（空结果/断层/JOIN 膨胀），
 * 输出 DATA_ISSUE 判断依据，避免把口径/数据问题当成业务真异常归因。</p>
 */
@Component
public class QualityIssueDetector {

    /**
     * 从归因查询执行结果组装质量信号。
     */
    public QualityFlags inspectQueries(List<QueryOutcome> outcomes) {
        QualityFlags flags = new QualityFlags();
        if (outcomes == null) {
            return flags;
        }
        for (QueryOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            if (outcome.isBlocked()) {
                flags.setSqlBlocked(true);
            }
            if (outcome.isExecuted() && outcome.getResult() != null) {
                Integer rowCount = outcome.getResult().getRowCount();
                if (rowCount != null && rowCount == 0) {
                    flags.setEmptyResult(true);
                }
                String check = outcome.getResultCheckJson();
                if (check != null && (check.contains("inflation") || check.contains("膨胀")
                        || check.contains("JOIN") || check.contains("join"))) {
                    flags.setJoinInflation(true);
                }
            }
        }
        return flags;
    }

    /**
     * 口径变更检测：比较发起时快照与最新口径版本。
     *
     * @param snapshot       发起时冻结口径
     * @param latestSnapshot 归因过程中最新口径（可为空=当前无口径）
     */
    public QualityFlags inspectCaliber(MetricKnowledgeSnapshot snapshot, MetricKnowledgeSnapshot latestSnapshot) {
        QualityFlags flags = new QualityFlags();
        if (snapshot == null || snapshot.getKbMetricId() == null
                || latestSnapshot == null || latestSnapshot.getKbMetricId() == null) {
            return flags;
        }
        if (snapshot.getKbMetricId().equals(latestSnapshot.getKbMetricId())
                && !java.util.Objects.equals(snapshot.getVersion(), latestSnapshot.getVersion())) {
            flags.setCaliberChange(true);
            flags.setReason("指标口径版本由 v" + snapshot.getVersion() + " 变更为 v" + latestSnapshot.getVersion()
                    + "，归因仍引用发起时口径（BR-001 口径冻结）");
        }
        return flags;
    }
}
