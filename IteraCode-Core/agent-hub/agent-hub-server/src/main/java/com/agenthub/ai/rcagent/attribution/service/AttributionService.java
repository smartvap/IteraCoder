package com.agenthub.ai.rcagent.attribution.service;

import com.agenthub.ai.rcagent.attribution.component.AnomalyDetector;
import com.agenthub.ai.rcagent.attribution.component.AttributionSqlBuilder;
import com.agenthub.ai.rcagent.attribution.component.ConclusionGenerator;
import com.agenthub.ai.rcagent.attribution.component.ContributionCalculator;
import com.agenthub.ai.rcagent.attribution.component.DrillPlanner;
import com.agenthub.ai.rcagent.attribution.component.EvidenceAssembler;
import com.agenthub.ai.rcagent.attribution.component.MetricKnowledgeResolver;
import com.agenthub.ai.rcagent.attribution.component.QualityIssueDetector;
import com.agenthub.ai.rcagent.attribution.component.ReviewCoordinator;
import com.agenthub.ai.rcagent.attribution.config.AttributionProperties;
import com.agenthub.ai.rcagent.attribution.constant.AttributionErrorCode;
import com.agenthub.ai.rcagent.attribution.constant.AttributionTaskStatus;
import com.agenthub.ai.rcagent.attribution.constant.CompareType;
import com.agenthub.ai.rcagent.attribution.constant.FactorStatus;
import com.agenthub.ai.rcagent.attribution.constant.NodeType;
import com.agenthub.ai.rcagent.attribution.constant.QueryReviewStatus;
import com.agenthub.ai.rcagent.attribution.constant.QueryType;
import com.agenthub.ai.rcagent.attribution.constant.ResultStatus;
import com.agenthub.ai.rcagent.attribution.constant.ReviewObject;
import com.agenthub.ai.rcagent.attribution.constant.ReviewState;
import com.agenthub.ai.rcagent.attribution.dto.AttributionAnalyzeRequest;
import com.agenthub.ai.rcagent.attribution.dto.AttributionFollowUpRequest;
import com.agenthub.ai.rcagent.attribution.dto.AttributionReviewDecision;
import com.agenthub.ai.rcagent.attribution.dto.AttributionReviewPageQuery;
import com.agenthub.ai.rcagent.attribution.dto.AttributionTaskPageQuery;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionFactor;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionNode;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionQueryLog;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionReviewLog;
import com.agenthub.ai.rcagent.attribution.entity.RcagentAttributionTask;
import com.agenthub.ai.rcagent.attribution.exception.AttributionException;
import com.agenthub.ai.rcagent.attribution.model.AnomalyResult;
import com.agenthub.ai.rcagent.attribution.model.ConfigOverride;
import com.agenthub.ai.rcagent.attribution.model.DrillNodeSummary;
import com.agenthub.ai.rcagent.attribution.model.DrillPlanStep;
import com.agenthub.ai.rcagent.attribution.model.MetricKnowledgeSnapshot;
import com.agenthub.ai.rcagent.attribution.model.QualityFlags;
import com.agenthub.ai.rcagent.attribution.model.QueryOutcome;
import com.agenthub.ai.rcagent.attribution.model.SegmentContribution;
import com.agenthub.ai.rcagent.attribution.model.TimeRange;
import com.agenthub.ai.rcagent.attribution.adapter.GuardAdapter;
import com.agenthub.ai.rcagent.attribution.vo.AnomalyVO;
import com.agenthub.ai.rcagent.attribution.vo.AttributionConfigVO;
import com.agenthub.ai.rcagent.attribution.vo.AttributionFactorVO;
import com.agenthub.ai.rcagent.attribution.vo.AttributionNodeVO;
import com.agenthub.ai.rcagent.attribution.vo.AttributionQueryVO;
import com.agenthub.ai.rcagent.attribution.vo.AttributionResultVO;
import com.agenthub.ai.rcagent.attribution.vo.AttributionReviewItemVO;
import com.agenthub.ai.rcagent.attribution.vo.AttributionTaskVO;
import com.agenthub.ai.rcagent.attribution.vo.ConclusionVO;
import com.agenthub.ai.rcagent.attribution.vo.DrillNodeSummaryVO;
import com.agenthub.ai.rcagent.attribution.vo.EvidenceChainItem;
import com.agenthub.ai.rcagent.attribution.vo.PendingReviewVO;
import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.context.BaseContext;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.rcagent.sqlguard.util.SqlTextUtil;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 归因分析主编排（AttributionService，S-1~S-8）。
 *
 * <p>analyze 编排：口径解析(BR-001 快照冻结) → 异常识别(总体/基期经 module-006 只读执行) →
 * 维度下钻/贡献拆解(GuardAdapter 全链路受控) → 结论生成/证据组装；MED/HIGH 归因查询进入
 * QUERY_REVIEWING、敏感/口径未verified/低置信结论进入 CONCLUSION_REVIEWING，等待 decideReview 驱动。
 * 归因 SQL 只读执行唯一通道 = module-006 executeGuarded（BLOCK 零放行）。</p>
 */
@Slf4j
@Service
public class AttributionService {

    /** 指标口径检索无主表/时间列时无法生成归因 SQL 的降级提示 */
    private static final String NO_SQL_CAPABILITY = "指标口径缺少主表或时间列，无法生成归因 SQL（请完善 kb_metric related_tables/related_columns）";
    private static final String SQL_BLOCKED_TIP = "归因 SQL 被安全体系拦截（BLOCK 零放行），建议检查指标口径/表映射后重试";
    private static final int SQL_ABSTRACT_MAX = 2000;
    private static final int HYPOTHESIS_MAX = 500;
    private static final int MAX_REWRITE_ROUND = 3;
    private static final String DS_ID_PATTERN = "^[a-z0-9][a-z0-9-_]*$";
    private static final String DATE_PATTERN = "^\\d{4}-\\d{2}-\\d{2}$";

    private final AttributionProperties properties;
    private final AttributionTaskStore store;
    private final MetricKnowledgeResolver metricResolver;
    private final AnomalyDetector anomalyDetector;
    private final DrillPlanner drillPlanner;
    private final AttributionSqlBuilder sqlBuilder;
    private final ContributionCalculator contributionCalculator;
    private final QualityIssueDetector qualityIssueDetector;
    private final ConclusionGenerator conclusionGenerator;
    private final EvidenceAssembler evidenceAssembler;
    private final ReviewCoordinator reviewCoordinator;
    private final GuardAdapter guardAdapter;

    public AttributionService(AttributionProperties properties,
                              AttributionTaskStore store,
                              MetricKnowledgeResolver metricResolver,
                              AnomalyDetector anomalyDetector,
                              DrillPlanner drillPlanner,
                              AttributionSqlBuilder sqlBuilder,
                              ContributionCalculator contributionCalculator,
                              QualityIssueDetector qualityIssueDetector,
                              ConclusionGenerator conclusionGenerator,
                              EvidenceAssembler evidenceAssembler,
                              ReviewCoordinator reviewCoordinator,
                              GuardAdapter guardAdapter) {
        this.properties = properties;
        this.store = store;
        this.metricResolver = metricResolver;
        this.anomalyDetector = anomalyDetector;
        this.drillPlanner = drillPlanner;
        this.sqlBuilder = sqlBuilder;
        this.contributionCalculator = contributionCalculator;
        this.qualityIssueDetector = qualityIssueDetector;
        this.conclusionGenerator = conclusionGenerator;
        this.evidenceAssembler = evidenceAssembler;
        this.reviewCoordinator = reviewCoordinator;
        this.guardAdapter = guardAdapter;
    }

    // ============================================================
    // S-1 归因主入口
    // ============================================================

    public AttributionResultVO analyze(AttributionAnalyzeRequest request) {
        validateAnalyzeRequest(request);
        ConfigOverride.EffectiveConfig cfg = resolveConfig(request.getConfigOverrides());
        Long operatorId = effectiveOperator(request.getOperatorId());
        String taskId = store.nextTaskId();

        RcagentAttributionTask task = new RcagentAttributionTask();
        task.setTaskId(taskId);
        task.setThreadId(truncate(request.getThreadId(), 64));
        task.setDsId(request.getDsId());
        task.setMetricName(truncate(request.getMetricName(), 128));
        task.setTriggerType(request.getTriggerType() == null ? com.agenthub.ai.rcagent.attribution.constant.TriggerType.USER_FOLLOW_UP.name() : request.getTriggerType());
        task.setCompareType(request.getCompareType() == null ? CompareType.MOM.name() : request.getCompareType());
        task.setQuestion(truncate(request.getQuestion(), 2000));
        if (request.getQueryContext() != null && !request.getQueryContext().isEmpty()) {
            task.setQueryContextJson(JSON.toJSONString(request.getQueryContext()));
        }
        TimeRange timeRange = buildTimeRange(request);
        task.setTimeRangeJson(JSON.toJSONString(timeRange));
        task.setStatus(AttributionTaskStatus.DETECTING.name());
        task.setReviewState(ReviewState.NONE.name());
        task.setDrillDepth(0);
        task.setQueryCount(0);
        task.setSensitiveFlag(0);
        task.setIsDeleted(0);
        store.insertTask(task);

        // BR-001 口径解析并冻结快照
        MetricKnowledgeSnapshot snapshot = metricResolver.resolve(request.getDsId(), request.getMetricName(),
                request.getMetricKbId());
        snapshot.setMetricName(request.getMetricName());
        task.setMetricName(request.getMetricName());
        boolean verified = Integer.valueOf(1).equals(snapshot.getVerified());
        task.setKbMetricId(snapshot.getKbMetricId());
        task.setMetricDefinitionVerified(verified ? 1 : 0);
        task.setMetricKnowledgeJson(JSON.toJSONString(snapshot));
        store.updateTask(task);

        // 口径能力：主表 + 时间列（决定能否走 SQL 归因）
        String mainTable = snapshot.mainTable();
        String timeColumn = snapshot.timeColumn();
        if (mainTable == null || timeColumn == null) {
            task.setStatus(AttributionTaskStatus.FINISHED.name());
            task.setReviewState(ReviewState.NONE.name());
            task.setResultStatus(ResultStatus.DEGRADED.name());
            task.setErrorMsg(NO_SQL_CAPABILITY);
            task.setConfidence(BigDecimal.ZERO);
            store.updateTask(task);
            return degradedResult(task, cfg, request.getMetricName(), snapshot, timeRange,
                    "指标口径不完整（主表/时间列缺失），归因降级为仅数据说明（BR-001）", verified);
        }

        // ROOT 节点（异常识别查询挂载点）
        RcagentAttributionNode root = new RcagentAttributionNode();
        String rootNodeId = store.nextNodeId(taskId, 1);
        root.setNodeId(rootNodeId);
        root.setTaskId(taskId);
        root.setLayer(0);
        root.setDimension("OVERALL");
        root.setDimensionLabel("总体");
        root.setNodeType(NodeType.ROOT.name());
        root.setDrillStatus("EXECUTED");
        root.setSortOrder(0);
        root.setIsDeleted(0);
        store.insertNode(root);

        // 异常识别：本期/基期总体经 module-006 受控执行
        task.setStatus(AttributionTaskStatus.DETECTING.name());
        store.updateTask(task);
        AnomalyResult anomaly = detectAnomaly(task, root, snapshot, timeRange, cfg, operatorId);
        task = store.findTask(taskId);
        task.setAnomalyJson(JSON.toJSONString(anomaly));
        task.setQualityFlagsJson(JSON.toJSONString(anomaly.getPseudoFlags() == null ? new QualityFlags() : anomaly.getPseudoFlags()));

        // 无异常 → STABLE 平稳说明；口径/数据问题 → DATA_ISSUE
        if (!anomaly.isAnomaly()) {
            task.setStatus(AttributionTaskStatus.EXPLAINING.name());
            boolean hasDataIssue = anomaly.getPseudoFlags() != null
                    && (anomaly.getPseudoFlags().isEmptyResult() || anomaly.getPseudoFlags().isDataGap()
                    || anomaly.getPseudoFlags().isSqlBlocked() || anomaly.getPseudoFlags().isCaliberChange());
            if (hasDataIssue) {
                task.setResultStatus(ResultStatus.DATA_ISSUE.name());
                task.setConclusionSummary(anomaly.getMessage() == null ? "检测到口径/数据质量信号，不做业务归因" : anomaly.getMessage());
            } else {
                task.setResultStatus(ResultStatus.STABLE.name());
                task.setConclusionSummary(anomaly.getMessage() == null ? "指标平稳（无异常）" : anomaly.getMessage());
            }
            task.setStatus(AttributionTaskStatus.FINISHED.name());
            task.setReviewState(ReviewState.NONE.name());
            store.updateTask(task);
            AttributionResultVO result = buildResultVO(task, false, false);
            result.setStage("DONE");
            result.setAnomaly(toAnomalyVO(anomaly));
            return result;
        }

        // 真异常 → 下钻拆解
        task.setStatus(AttributionTaskStatus.DRILLING.name());
        task.setReviewState(ReviewState.NONE.name());
        store.updateTask(task);
        return runDrillAndConclude(task, snapshot, timeRange, anomaly, cfg, operatorId, request.getDimensions(), null);
    }

    // ============================================================
    // S-2 追问下钻
    // ============================================================

    public AttributionResultVO followUp(AttributionFollowUpRequest request) {
        if (request == null || request.getTaskId() == null || request.getTaskId().isBlank()) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "task_id 不能为空");
        }
        RcagentAttributionTask task = store.findTask(request.getTaskId());
        store.requireTask(task, "归因任务不存在");
        String status = task.getStatus();
        boolean finishedDrillable = AttributionTaskStatus.FINISHED.name().equals(status);
        if (!finishedDrillable && !AttributionTaskStatus.CONCLUDING.name().equals(status)
                && !AttributionTaskStatus.DRILLING.name().equals(status)
                && !AttributionTaskStatus.EXECUTING.name().equals(status)) {
            throw new BusinessException(AttributionErrorCode.TASK_NOT_FOUND_OR_STATE,
                    "任务状态不允许追问（当前 " + status + "），仅 FINISHED/DRILLING 可继续下钻");
        }
        ConfigOverride.EffectiveConfig cfg = resolveConfig(request.getConfigOverrides());
        Long operatorId = effectiveOperator(request.getOperatorId());
        MetricKnowledgeSnapshot snapshot = MetricKnowledgeSnapshot.fromJson(task.getMetricKnowledgeJson());
        TimeRange timeRange = parseTimeRange(task.getTimeRangeJson());
        AnomalyResult anomaly = parseAnomaly(task.getAnomalyJson());

        if (Boolean.TRUE.equals(request.getResetPath())) {
            // reset_path=true：清空已下钻路径从首层重来
            store.softDeleteNodesDeeperThan(task.getTaskId(), 0);
            task.setDrillDepth(0);
            task.setDrillPathJson(null);
            task.setQueryCount(0);
            task.setStatus(AttributionTaskStatus.DRILLING.name());
            task.setReviewState(ReviewState.NONE.name());
            store.updateTask(task);
            return runDrillAndConclude(task, snapshot, timeRange, anomaly, cfg, operatorId, request.getDimensions(), null);
        }
        return runDrillAndConclude(task, snapshot, timeRange, anomaly, cfg, operatorId, request.getDimensions(),
                request.getFocusFactor());
    }

    // ============================================================
    // S-3 任务详情 / S-4 历史分页 / S-5 审核分页 / S-6 决策 / S-7 取消 / S-8 配置
    // ============================================================

    public AttributionTaskVO getTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "task_id 不能为空");
        }
        RcagentAttributionTask task = store.requireTask(store.findTask(taskId), "归因任务不存在");
        return AttributionTaskVO.from(task, AttributionTaskStore.anomalyDirection(task.getAnomalyJson()));
    }

    /** HTTP 详情（含下钻树/因子/查询/结论/审核留痕） */
    public AttributionResultVO getDetail(String taskId, boolean includeSql) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "task_id 不能为空");
        }
        RcagentAttributionTask task = store.requireTask(store.findTask(taskId), "归因任务不存在");
        if (includeSql) {
            // 受控 SQL 门闸：sql_text 仅审核角色可见（api-contract 接口 3，无权限返回 40300）
            checkSqlViewPermission();
        }
        return buildResultVO(task, true, includeSql);
    }

    /**
     * 审核 SQL 展示权限门闸（接口 3 include_sql=true）。
     *
     * <p>当前项目 JWT 校验为占位实现（BaseContext.userId 由拦截器硬编码），无法从 token 解析角色，
     * 最小实现采用可配置审核人名单（agenthub.attribution.review.reviewer-user-ids）判定：
     * 调用方 userId 不在名单内一律 40300 拒绝且不回传 sql_text，默认名单为空（fail-closed）。
     * TODO 真实 RBAC 落地后在此替换为角色/权限点校验。</p>
     */
    private void checkSqlViewPermission() {
        List<Long> reviewerIds = properties.reviewerUserIds();
        Long currentUserId = BaseContext.getCurrentId();
        if (currentUserId == null || reviewerIds.isEmpty() || !reviewerIds.contains(currentUserId)) {
            log.warn("include_sql=true 被拒绝（40300）：调用方 userId={} 不在审核人名单", currentUserId);
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "SQL 仅审核角色可见");
        }
    }

    public PageResult pageTasks(AttributionTaskPageQuery query) {
        if (query == null) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "请求参数不能为空");
        }
        if (query.getStatus() != null && !query.getStatus().isBlank()
                && !AttributionTaskStatus.isValid(query.getStatus())) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "status 非法");
        }
        if (query.getResultStatus() != null && !query.getResultStatus().isBlank()
                && !ResultStatus.isValid(query.getResultStatus())) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "result_status 非法");
        }
        return store.pageTasks(query);
    }

    public PageResult pageReviews(AttributionReviewPageQuery query) {
        if (query == null) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "请求参数不能为空");
        }
        if (query.getReviewObject() != null && !query.getReviewObject().isBlank()
                && !ReviewObject.isValid(query.getReviewObject())) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "review_object 非法");
        }
        if (query.getDecision() != null && !query.getDecision().isBlank()
                && !com.agenthub.ai.rcagent.attribution.constant.ReviewDecision.isValid(query.getDecision())) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "decision 非法");
        }
        return store.pageReviews(query);
    }

    public AttributionResultVO decideReview(AttributionReviewDecision decision) {
        validateDecision(decision);
        RcagentAttributionTask task = store.requireTask(store.findTask(decision.getTaskId()), "归因任务不存在");
        Long operatorId = effectiveOperator(decision.getReviewerId());
        String reviewObject = decision.getReviewObject();
        String decisionValue = decision.getDecision();
        int nextRound = (task.getReviewState() == null || task.getReviewState().isBlank())
                ? 1 : reviewRoundOf(task.getTaskId()) + 1;

        if (ReviewObject.QUERY.name().equals(reviewObject)) {
            return decideQueryReview(task, decision, decisionValue, operatorId, nextRound);
        }
        return decideConclusionReview(task, decision, decisionValue, operatorId, nextRound);
    }

    /**
     * 取消任务或回退到指定下钻层（接口 7，S-7）。
     *
     * <p>返回更新后的任务对象供 Controller 组装强类型响应（契约 data.task），避免 Controller
     * 自行拼装弱类型 Map。取消场景 status=CANCELLED；回退场景 status=DRILLING 且 drill_depth 回落。</p>
     *
     * @return 更新后的任务（已落库；任务不存在抛 44104）
     */
    public RcagentAttributionTask cancelOrReset(String taskId, Integer resetToLayer) {
        if (taskId == null || taskId.isBlank()) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "task_id 不能为空");
        }
        RcagentAttributionTask task = store.requireTask(store.findTask(taskId), "归因任务不存在");
        if (AttributionTaskStatus.CANCELLED.name().equals(task.getStatus())) {
            return task; // 幂等收敛：已取消直接返回当前任务视图
        }
        if (resetToLayer == null || resetToLayer < 0) {
            task.setStatus(AttributionTaskStatus.CANCELLED.name());
            task.setReviewState(ReviewState.NONE.name());
            store.updateTask(task);
            return task;
        }
        int resetLayer = Math.min(resetToLayer, task.getDrillDepth() == null ? 0 : task.getDrillDepth());
        store.softDeleteNodesDeeperThan(taskId, resetLayer);
        task.setDrillDepth(resetLayer);
        task.setStatus(AttributionTaskStatus.DRILLING.name());
        task.setReviewState(ReviewState.NONE.name());
        store.updateTask(task);
        return task;
    }

    public AttributionConfigVO getConfig() {
        AttributionConfigVO vo = new AttributionConfigVO();
        AttributionConfigVO.AnomalyConfig anomaly = new AttributionConfigVO.AnomalyConfig();
        anomaly.setMomThresholdRate(properties.momThresholdRateValue());
        anomaly.setYoyThresholdRate(properties.yoyThresholdRateValue());
        anomaly.setMinDeltaAbs(properties.minDeltaAbsValue());
        anomaly.setContinuityThreshold(properties.continuityThresholdValue());
        vo.setAnomaly(anomaly);
        AttributionConfigVO.DrillConfig drill = new AttributionConfigVO.DrillConfig();
        drill.setMaxDepth(properties.maxDepthValue());
        drill.setMaxQueries(properties.maxQueriesValue());
        drill.setTopSegments(properties.topSegmentsValue());
        vo.setDrill(drill);
        AttributionConfigVO.ContributionConfig contribution = new AttributionConfigVO.ContributionConfig();
        contribution.setPositiveMajority(properties.positiveMajorityValue());
        vo.setContribution(contribution);
        AttributionConfigVO.ReviewConfig review = new AttributionConfigVO.ReviewConfig();
        review.setMvpReviewAll(properties.mvpReviewAllValue());
        review.setHighReviewRequired(properties.highReviewRequiredValue());
        vo.setReview(review);
        return vo;
    }

    // ============================================================
    // 异常识别
    // ============================================================

    private AnomalyResult detectAnomaly(RcagentAttributionTask task, RcagentAttributionNode root,
                                        MetricKnowledgeSnapshot snapshot, TimeRange timeRange,
                                        ConfigOverride.EffectiveConfig cfg, Long operatorId) {
        List<QueryOutcome> outcomes = new ArrayList<>();
        // 本期总体
        String curSql = sqlBuilder.buildTotalSql(snapshot.mainTable(), snapshot.timeColumn(),
                timeRange.getCurrent().getStart(), timeRange.getCurrent().getEnd());
        QueryOutcome cur = runGuarded(task, root.getNodeId(), QueryType.OVERALL.name(), curSql, false, operatorId);
        outcomes.add(cur);
        // 基期总体
        String baseSql = sqlBuilder.buildTotalSql(snapshot.mainTable(), snapshot.timeColumn(),
                timeRange.getBase().getStart(), timeRange.getBase().getEnd());
        QueryOutcome base = runGuarded(task, root.getNodeId(), QueryType.BASELINE.name(), baseSql, false, operatorId);
        outcomes.add(base);
        persistQueryLogs(task, outcomes);

        BigDecimal curTotal = executedTotal(cur);
        BigDecimal baseTotal = executedTotal(base);
        QualityFlags flags = qualityIssueDetector.inspectQueries(outcomes);
        // 口径变更检测
        MetricKnowledgeSnapshot latest = metricResolver.resolveLatest(task.getDsId(), task.getMetricName());
        QualityFlags caliber = qualityIssueDetector.inspectCaliber(snapshot, latest);
        if (caliber != null) {
            if (flags == null) {
                flags = caliber;
            } else {
                flags.setCaliberChange(flags.isCaliberChange() || caliber.isCaliberChange());
                if (flags.getReason() == null && caliber.getReason() != null) {
                    flags.setReason(caliber.getReason());
                }
            }
        }
        // 总体未执行（被 BLOCK/依赖失败）按数据问题信号处理，避免无依据判异常
        if (curTotal == null || baseTotal == null) {
            if (flags == null) {
                flags = new QualityFlags();
            }
            flags.setDataGap(true);
        }
        AnomalyResult result = anomalyDetector.judge(curTotal, baseTotal, cfg, flags);
        // 总 SQL 也被拦截时直接标记伪异常信号
        if ((cur.isBlocked() || base.isBlocked()) && result.getPseudoFlags() != null) {
            result.getPseudoFlags().setSqlBlocked(true);
        }
        return result;
    }

    private BigDecimal executedTotal(QueryOutcome outcome) {
        if (outcome == null || !outcome.isExecuted() || outcome.getResult() == null
                || outcome.getResult().getRows() == null || outcome.getResult().getRows().isEmpty()) {
            return null;
        }
        return DrillNodeSummary.totalOf(outcome.getResult().getRows(), null);
    }

    // ============================================================
    // 下钻 + 结论
    // ============================================================

    private AttributionResultVO runDrillAndConclude(RcagentAttributionTask task, MetricKnowledgeSnapshot snapshot,
                                                    TimeRange timeRange, AnomalyResult anomaly,
                                                    ConfigOverride.EffectiveConfig cfg, Long operatorId,
                                                    List<String> dimensions,
                                                    AttributionFollowUpRequest.FocusFactor focus) {
        String taskId = task.getTaskId();
        String metric = task.getMetricName();
        List<String> drilled = parseDrilledDimensions(task.getDrillPathJson());
        List<DrillPlanStep> steps = planSteps(dimensions, snapshot, cfg.getMaxDepth(), drilled, task, focus);

        List<String> finalResult = new ArrayList<>();
        int depth = task.getDrillDepth() == null ? 0 : task.getDrillDepth();
        RcagentAttributionNode lastNode = null;
        boolean stoppedByReview = false;
        boolean stoppedByBlocked = false;
        List<RcagentAttributionNode> newNodes = new ArrayList<>();

        for (DrillPlanStep step : steps) {
            if (step == null || step.getColumn() == null) {
                continue;
            }
            if (task.getQueryCount() + 2 > cfg.getMaxQueries()) {
                finalResult.add("已达归因查询次数上限（max_queries=" + cfg.getMaxQueries() + "），停止下钻建议人工分析（BR-004）");
                break;
            }
            if (depth + 1 > cfg.getMaxDepth()) {
                finalResult.add("已达下钻深度上限（max_depth=" + cfg.getMaxDepth() + "），停止下钻建议人工分析（BR-004）");
                break;
            }
            int nodeSeq = store.nextNodeSeq(taskId);
            String nodeId = store.nextNodeId(taskId, nodeSeq);
            int newLayer = depth + 1;
            RcagentAttributionNode node = new RcagentAttributionNode();
            node.setNodeId(nodeId);
            node.setTaskId(taskId);
            node.setLayer(newLayer);
            node.setDimension(step.getDimension());
            node.setDimensionLabel(step.getDimensionLabel());
            node.setNodeType(NodeType.GROUP_DRILL.name());
            node.setParentNodeId(lastNode == null ? rootNodeId(taskId) : lastNode.getNodeId());
            if (step.getFocusSegmentKey() != null) {
                node.setDimensionValue(step.getFocusSegmentKey());
                node.setNodeType(NodeType.CROSS_DRILL.name());
            }
            node.setDrillStatus("REVIEWING");
            node.setSortOrder(newLayer);
            node.setIsDeleted(0);
            store.insertNode(node);

            // 本期/基期分组 SQL（focus 子群时交叉下钻）
            String focusColumn = focusColumnOf(task, snapshot, step, lastNode);
            String groupCurSql = buildGroupSql(snapshot, timeRange, step, focusColumn, true);
            String groupBaseSql = buildGroupSql(snapshot, timeRange, step, focusColumn, false);
            QueryOutcome cur = runGuarded(task, nodeId, QueryType.GROUP_CUR.name(), groupCurSql, false, operatorId);
            QueryOutcome base = runGuarded(task, nodeId, QueryType.GROUP_BASE.name(), groupBaseSql, false, operatorId);
            persistQueryLogs(task, List.of(cur, base));

            // MED/HIGH 归因查询 → 进入 QUERY_REVIEWING
            if (requiresQueryReview(cur) || requiresQueryReview(base)) {
                node.setDrillStatus("REVIEWING");
                store.updateNode(node);
                task.setStatus(AttributionTaskStatus.QUERY_REVIEWING.name());
                task.setReviewState(ReviewState.QUERY_REVIEWING.name());
                task.setDrillDepth(depth);
                store.updateTask(task);
                stoppedByReview = true;
                break;
            }
            if (cur.isBlocked() || base.isBlocked()) {
                node.setDrillStatus("FAILED");
                store.updateNode(node);
                task.setQualityFlagsJson(JSON.toJSONString(mergeFlags(task, true)));
                stoppedByBlocked = true;
                break;
            }
            // 贡献拆解落库
            DrillNodeSummary summary = computeAndPersistFactors(task, node, step, cur, base, cfg);
            node.setDrillStatus("EXECUTED");
            node.setCompareSummaryJson(JSON.toJSONString(summary));
            node.setQueryIds(JSON.toJSONString(Arrays.asList(cur.getQueryId(), base.getQueryId())));
            node.setFactorIds(JSON.toJSONString(factorIdsByNode(taskId, nodeId)));
            store.updateNode(node);
            newNodes.add(node);
            lastNode = node;
            depth = newLayer;
            task = store.findTask(taskId);
            task.setDrillDepth(depth);
            appendDrillPath(task, step, node);
            store.updateTask(task);
        }

        task = store.findTask(taskId);
        if (stoppedByReview) {
            AttributionResultVO result = buildResultVO(task, false, false);
            result.setStage("QUERY_REVIEWING");
            result.setPendingReview(pendingReviewOf(task, null, "归因下钻 SQL 命中 MEDIUM/HIGH，需人工放行（BR-002）"));
            return result;
        }
        if (stoppedByBlocked) {
            task.setStatus(AttributionTaskStatus.FINISHED.name());
            task.setReviewState(ReviewState.NONE.name());
            task.setResultStatus(ResultStatus.DEGRADED.name());
            task.setErrorMsg(SQL_BLOCKED_TIP);
            task.setConclusionSummary(SQL_BLOCKED_TIP);
            store.updateTask(task);
            AttributionResultVO result = buildResultVO(task, false, false);
            result.setStage("DEGRADED");
            result.setDegraded(degraded(metric, cfg, task, SQL_BLOCKED_TIP));
            return result;
        }

        if (!finalResult.isEmpty() && newNodes.isEmpty()) {
            task.setStatus(AttributionTaskStatus.FINISHED.name());
            task.setReviewState(ReviewState.NONE.name());
            task.setResultStatus(ResultStatus.DEGRADED.name());
            task.setConclusionSummary(finalResult.get(0));
            task.setErrorMsg(finalResult.get(0));
            store.updateTask(task);
            AttributionResultVO result = buildResultVO(task, false, false);
            result.setStage("DEGRADED");
            result.setDegraded(degraded(metric, cfg, task, finalResult.get(0)));
            return result;
        }

        // 结论生成
        return conclude(task, snapshot, anomaly, cfg, operatorId);
    }

    private AttributionResultVO conclude(RcagentAttributionTask task, MetricKnowledgeSnapshot snapshot,
                                         AnomalyResult anomaly, ConfigOverride.EffectiveConfig cfg, Long operatorId) {
        List<RcagentAttributionFactor> factors = store.listFactorsByTask(task.getTaskId());
        if (factors == null || factors.isEmpty()) {
            task.setStatus(AttributionTaskStatus.FINISHED.name());
            task.setReviewState(ReviewState.NONE.name());
            task.setResultStatus(ResultStatus.DATA_ISSUE.name());
            task.setConclusionSummary("下钻未产生候选因子（分组结果为空或全部被安全拦截），不输出业务归因结论");
            store.updateTask(task);
            AttributionResultVO result = buildResultVO(task, false, false);
            result.setStage("DONE");
            result.setAnomaly(toAnomalyVO(anomaly));
            return result;
        }
        Map<String, RcagentAttributionQueryLog> logIndex = store.indexQueryLogsByTask(task.getTaskId());
        boolean sensitive = isSensitiveTask(task, factors, snapshot);
        ConclusionVO conclusion = conclusionGenerator.generate(new ConclusionGenerator.GenerateContext(
                task.getMetricName(), anomaly.getDirection(), anomaly.getCurTotal(), anomaly.getBaseTotal(),
                null, factors, snapshot, logIndex, sensitive));
        String summary = conclusionGenerator.summary(conclusion);
        conclusion.setConfidence(conclusion.getConfidence());
        ReviewCoordinator.ConclusionReviewReason reason = reviewCoordinator.conclusionNeedsReview(
                sensitive, Integer.valueOf(1).equals(task.getMetricDefinitionVerified()),
                conclusion.getConfidence(), anomaly.getSeverity(), cfg);

        // 因子状态标记（主因/次要）
        markFactorStatuses(factors, conclusion);

        task.setSensitiveFlag(sensitive ? 1 : 0);
        task.setConclusionJson(JSON.toJSONString(conclusion));
        task.setConclusionSummary(summary);
        task.setConfidence(conclusion.getConfidence());
        task.setStatus(AttributionTaskStatus.CONCLUDING.name());
        store.updateTask(task);

        if (reason.isNeedsReview()) {
            task.setStatus(AttributionTaskStatus.CONCLUSION_REVIEWING.name());
            task.setReviewState(ReviewState.CONCLUSION_REVIEWING.name());
            store.updateTask(task);
            AttributionResultVO result = buildResultVO(task, false, false);
            result.setStage("CONCLUSION_REVIEWING");
            result.setConclusion(conclusion);
            result.setSummary(summary);
            result.setPendingReview(pendingReviewOf(task, reason, null));
            return result;
        }
        task.setStatus(AttributionTaskStatus.FINISHED.name());
        task.setReviewState(ReviewState.NONE.name());
        task.setResultStatus(ResultStatus.CONCLUSION.name());
        store.updateTask(task);
        AttributionResultVO result = buildResultVO(task, true, false);
        result.setStage("DONE");
        result.setAnomaly(toAnomalyVO(anomaly));
        result.setConclusion(conclusion);
        result.setSummary(summary);
        result.setFactors(factorVOs(factors));
        result.setMainFactors(mainFactorVOs(factors));
        return result;
    }

    // ============================================================
    // 下钻执行辅助
    // ============================================================

    private List<DrillPlanStep> planSteps(List<String> dimensions, MetricKnowledgeSnapshot snapshot, int maxDepth,
                                          List<String> drilled, RcagentAttributionTask task,
                                          AttributionFollowUpRequest.FocusFactor focus) {
        List<DrillPlanStep> steps = drillPlanner.plan(dimensions, snapshot, maxDepth, drilled);
        if (focus != null && focus.getDimension() != null && !focus.getDimension().isBlank()) {
            DrillPlanStep focusStep = drillPlanner.planWithFocus(focus.getDimension(), snapshot,
                    focus.getSegmentKey(), focus.getSegmentValue());
            if (focusStep != null) {
                List<DrillPlanStep> onlyFocus = new ArrayList<>();
                onlyFocus.add(focusStep);
                return onlyFocus;
            }
        }
        return steps;
    }

    /** focus 子群在父层维度过滤（focus 下钻时父层为上一成功节点维度） */
    private String focusColumnOf(RcagentAttributionTask task, MetricKnowledgeSnapshot snapshot,
                                 DrillPlanStep step, RcagentAttributionNode lastNode) {
        if (step.getFocusSegmentKey() == null || lastNode == null) {
            return null;
        }
        return snapshot.matchColumn(lastNode.getDimension());
    }

    private String buildGroupSql(MetricKnowledgeSnapshot snapshot, TimeRange timeRange, DrillPlanStep step,
                                 String focusColumn, boolean cur) {
        String start = cur ? timeRange.getCurrent().getStart() : timeRange.getBase().getStart();
        String end = cur ? timeRange.getCurrent().getEnd() : timeRange.getBase().getEnd();
        int topN = properties.topSegmentsValue();
        if (focusColumn != null && step.getFocusSegmentKey() != null) {
            return sqlBuilder.buildCrossGroupSql(snapshot.mainTable(), snapshot.timeColumn(), start, end,
                    step.getColumn(), focusColumn, step.getFocusSegmentKey(), topN);
        }
        return sqlBuilder.buildGroupSql(snapshot.mainTable(), snapshot.timeColumn(), start, end,
                step.getColumn(), topN);
    }

    private DrillNodeSummary computeAndPersistFactors(RcagentAttributionTask task, RcagentAttributionNode node,
                                                      DrillPlanStep step, QueryOutcome cur, QueryOutcome base,
                                                      ConfigOverride.EffectiveConfig cfg) {
        BigDecimal curTotal = executedTotal(cur);
        BigDecimal baseTotal = executedTotal(base);
        BigDecimal curT = curTotal == null ? BigDecimal.ZERO : curTotal;
        BigDecimal baseT = baseTotal == null ? BigDecimal.ZERO : baseTotal;
        List<SegmentContribution> contributions = contributionCalculator.compute(
                cur.getResult() == null ? List.of() : cur.getResult().getRows(),
                base.getResult() == null ? List.of() : base.getResult().getRows(),
                curT, baseT, step.getDimension(), step.getDimensionLabel(),
                Arrays.asList(cur.getQueryId(), base.getQueryId()),
                cfg.isPositiveMajority(), cfg.getTopSegments());
        List<RcagentAttributionFactor> factorEntities = new ArrayList<>();
        for (SegmentContribution sc : contributions) {
            RcagentAttributionFactor factor = new RcagentAttributionFactor();
            factor.setFactorId(store.nextFactorId());
            factor.setTaskId(task.getTaskId());
            factor.setNodeId(node.getNodeId());
            factor.setFactorType(sc.getFactorType());
            factor.setDimension(sc.getDimension());
            factor.setDimensionLabel(sc.getDimensionLabel());
            factor.setSegmentKey(sc.getSegmentKey());
            factor.setSegmentValue(sc.getSegmentValue());
            factor.setCurValue(sc.getCurValue());
            factor.setBaseValue(sc.getBaseValue());
            factor.setDeltaValue(sc.getDeltaValue());
            factor.setDeltaRatio(sc.getDeltaRatio());
            factor.setContribution(sc.getContribution());
            factor.setContributionRatio(sc.getContributionRatio());
            factor.setCurShare(sc.getCurShare());
            factor.setBaseShare(sc.getBaseShare());
            factor.setSegmentChange(sc.getSegmentChange());
            factor.setIsMain(sc.isMain() ? 1 : 0);
            factor.setRank(sc.getRank());
            factor.setConfidence(estimateFactorConfidence(sc));
            factor.setHypothesis(hypothesisOf(sc, cfg));
            if (sc.getEvidenceQueryIds() != null) {
                factor.setEvidenceQueryIds(JSON.toJSONString(sc.getEvidenceQueryIds()));
            }
            factor.setSensitiveFlag(0);
            factor.setStatus(FactorStatus.CANDIDATE.name());
            factor.setIsDeleted(0);
            store.insertFactor(factor);
            factorEntities.add(factor);
        }
        DrillNodeSummary summary = new DrillNodeSummary();
        summary.setCurTotal(curT);
        summary.setBaseTotal(baseT);
        summary.setDelta(curT.subtract(baseT));
        summary.setDeltaRatio(rateOf(curT, baseT));
        summary.setSegmentCount(contributions.size());
        List<DrillNodeSummary.TopSegment> top = new ArrayList<>();
        for (SegmentContribution sc : contributions) {
            DrillNodeSummary.TopSegment seg = new DrillNodeSummary.TopSegment();
            seg.setDimension(sc.getDimension());
            seg.setSegmentKey(sc.getSegmentKey());
            seg.setSegmentValue(sc.getSegmentValue());
            seg.setCur(sc.getCurValue());
            seg.setBase(sc.getBaseValue());
            seg.setDelta(sc.getDeltaValue());
            seg.setContributionRatio(sc.getContributionRatio());
            seg.setMain(sc.isMain());
            top.add(seg);
        }
        summary.setTopSegments(top);
        return summary;
    }

    private BigDecimal estimateFactorConfidence(SegmentContribution sc) {
        double base = 0.7;
        double evidence = (sc.getEvidenceQueryIds() == null || sc.getEvidenceQueryIds().isEmpty()) ? 0.7 : 1.0;
        double deltaFactor = sc.getDeltaValue() == null || sc.getDeltaValue().signum() == 0 ? 0.5 : 1.0;
        double confidence = Math.min(0.98, Math.max(0.1, base * evidence * deltaFactor));
        return BigDecimal.valueOf(confidence).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private String hypothesisOf(SegmentContribution sc, ConfigOverride.EffectiveConfig cfg) {
        String label = sc.getDimensionLabel() == null ? sc.getDimension() : sc.getDimensionLabel();
        String template = "在「%s」维度观测到子群「%s」本期 %.2f / 基期 %.2f、变化量 %s，需结合业务动作核实原因（人工假设，非因果断言，BR-003）";
        return SqlTextUtil.truncate(String.format(template, label, sc.getSegmentValue(),
                nz(sc.getCurValue()), nz(sc.getBaseValue()), sc.getDeltaValue() == null ? "0" : sc.getDeltaValue().stripTrailingZeros().toPlainString()),
                HYPOTHESIS_MAX);
    }

    private void markFactorStatuses(List<RcagentAttributionFactor> factors, ConclusionVO conclusion) {
        if (conclusion.getMainFactor() == null || conclusion.getMainFactor().getFactorId() == null) {
            return;
        }
        java.util.Set<String> mainIds = new java.util.HashSet<>();
        mainIds.add(conclusion.getMainFactor().getFactorId());
        if (conclusion.getSecondaryFactors() != null) {
            for (ConclusionVO.FactorBrief brief : conclusion.getSecondaryFactors()) {
                if (brief.getFactorId() != null) {
                    mainIds.add(brief.getFactorId());
                }
            }
        }
        for (RcagentAttributionFactor factor : factors) {
            if (factor == null || factor.getId() == null) {
                continue;
            }
            String target = mainIds.contains(factor.getFactorId())
                    ? (factor.getFactorId().equals(conclusion.getMainFactor().getFactorId())
                    ? FactorStatus.MAIN_FACTOR.name() : FactorStatus.SECONDARY.name())
                    : FactorStatus.CANDIDATE.name();
            if (!target.equals(factor.getStatus())) {
                factor.setStatus(target);
                store.updateFactor(factor);
            }
        }
    }

    // ============================================================
    // 审核决策
    // ============================================================

    private AttributionResultVO decideQueryReview(RcagentAttributionTask task, AttributionReviewDecision decision,
                                                  String decisionValue, Long operatorId, int round) {
        if (!ReviewState.QUERY_REVIEWING.name().equals(task.getReviewState())
                && !AttributionTaskStatus.QUERY_REVIEWING.name().equals(task.getStatus())) {
            throw new BusinessException(AttributionErrorCode.REVIEW_IDEMPOTENT_CONFLICT,
                    "该归因任务不处于 QUERY_REVIEWING，禁止审核（状态冲突/重复审核）");
        }
        List<String> queryIds = decision.getQueryIds();
        if (queryIds == null || queryIds.isEmpty()) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "review_object=QUERY 必须提供 query_ids");
        }
        List<RcagentAttributionQueryLog> pendingLogs = store.listQueryLogsByTask(task.getTaskId()).stream()
                .filter(q -> queryIds.contains(q.getQueryId()))
                .filter(q -> QueryReviewStatus.NONE.name().equals(q.getReviewStatus())
                        || QueryReviewStatus.REJECTED.name().equals(q.getReviewStatus()))
                .collect(Collectors.toList());
        if (pendingLogs.isEmpty()) {
            throw new BusinessException(AttributionErrorCode.REVIEW_IDEMPOTENT_CONFLICT, "该归因查询已审核，禁止重复审核");
        }

        for (RcagentAttributionQueryLog logEntity : pendingLogs) {
            RcagentAttributionReviewLog reviewLog = new RcagentAttributionReviewLog();
            reviewLog.setReviewId(store.nextReviewId());
            reviewLog.setTaskId(task.getTaskId());
            reviewLog.setThreadId(task.getThreadId());
            reviewLog.setReviewObject(ReviewObject.QUERY.name());
            reviewLog.setQueryId(logEntity.getQueryId());
            reviewLog.setRiskLevel(logEntity.getRiskLevel());
            reviewLog.setDecision(decisionValue);
            reviewLog.setComment(truncate(decision.getComment(), 1000));
            reviewLog.setReviewerId(operatorId);
            reviewLog.setReviewRound(round);
            reviewLog.setIsDeleted(0);
            store.insertReviewLog(reviewLog);

            if (com.agenthub.ai.rcagent.attribution.constant.ReviewDecision.APPROVE.name().equals(decisionValue)) {
                logEntity.setReviewStatus(QueryReviewStatus.APPROVED.name());
                store.updateQueryLog(logEntity);
            } else {
                logEntity.setReviewStatus(QueryReviewStatus.REJECTED.name());
                store.updateQueryLog(logEntity);
            }
        }

        if (com.agenthub.ai.rcagent.attribution.constant.ReviewDecision.APPROVE.name().equals(decisionValue)) {
            return resumeAfterQueryApproval(task);
        }
        // REJECT：默认 SKIP 该因子/维度（RETRY 语义：回退到该层重新尝试由人工在后续追问发起，避免无限重试）
        return rejectAndSkipQueryLayer(task, decision);
    }

    private AttributionResultVO resumeAfterQueryApproval(RcagentAttributionTask task) {
        // 找到 REVIEWING 节点（审核放行后应继续执行该层与后续结论）
        List<RcagentAttributionNode> nodes = store.listNodesByTask(task.getTaskId());
        RcagentAttributionNode pending = null;
        for (RcagentAttributionNode n : nodes) {
            if ("REVIEWING".equals(n.getDrillStatus())) {
                pending = n;
                break;
            }
        }
        ConfigOverride.EffectiveConfig cfg = resolveConfig(null);
        MetricKnowledgeSnapshot snapshot = MetricKnowledgeSnapshot.fromJson(task.getMetricKnowledgeJson());
        TimeRange timeRange = parseTimeRange(task.getTimeRangeJson());
        AnomalyResult anomaly = parseAnomaly(task.getAnomalyJson());

        if (pending != null) {
            // 放行已审核查询（executeGuarded requireManualApproval=true）
            final String pendingNodeId = pending.getNodeId();
            List<RcagentAttributionQueryLog> logs = store.listQueryLogsByTask(task.getTaskId()).stream()
                    .filter(q -> pendingNodeId.equals(q.getNodeId()))
                    .collect(Collectors.toList());
            for (RcagentAttributionQueryLog logEntity : logs) {
                if (QueryReviewStatus.APPROVED.name().equals(logEntity.getReviewStatus())
                        && !Integer.valueOf(1).equals(logEntity.getExecuted())) {
                    QueryOutcome outcome = new QueryOutcome();
                    outcome.setQueryId(logEntity.getQueryId());
                    outcome.setQueryType(logEntity.getQueryType());
                    outcome.setNodeId(pending.getNodeId());
                    outcome.setDsId(task.getDsId());
                    outcome.setSqlText(logEntity.getSqlText());
                    outcome.setSqlAbstract(logEntity.getSqlAbstract());
                    guardAdapter.guard(outcome, true);
                    updateQueryLogEntity(logEntity, outcome);
                    logEntity.setExecuted(1);
                    store.updateQueryLog(logEntity);
                }
            }
            // 重新取 cur/base 结果，计算因子
            List<RcagentAttributionQueryLog> groupLogs = store.listQueryLogsByTask(task.getTaskId()).stream()
                    .filter(q -> pendingNodeId.equals(q.getNodeId()) && Integer.valueOf(1).equals(q.getExecuted()))
                    .collect(Collectors.toList());
            QueryOutcome curOutcome = toOutcomeFromLog(groupLogs, QueryType.GROUP_CUR.name());
            QueryOutcome baseOutcome = toOutcomeFromLog(groupLogs, QueryType.GROUP_BASE.name());
            if (curOutcome == null || baseOutcome == null) {
                // 缺少一期结果：该层打回失败
                task.setStatus(AttributionTaskStatus.FINISHED.name());
                task.setReviewState(ReviewState.NONE.name());
                task.setResultStatus(ResultStatus.DEGRADED.name());
                task.setErrorMsg("归因查询放行后未取得完整分组结果，无法继续拆解");
                store.updateTask(task);
                AttributionResultVO degraded = buildResultVO(task, false, false);
                degraded.setStage("DEGRADED");
                return degraded;
            }
            DrillPlanStep step = new DrillPlanStep();
            step.setDimension(pending.getDimension());
            step.setDimensionLabel(pending.getDimensionLabel());
            step.setColumn(snapshot.matchColumn(pending.getDimension()));
            DrillNodeSummary summary = computeAndPersistFactors(task, pending, step, curOutcome, baseOutcome, cfg);
            pending.setDrillStatus("EXECUTED");
            pending.setCompareSummaryJson(JSON.toJSONString(summary));
            pending.setQueryIds(JSON.toJSONString(groupLogs.stream().map(RcagentAttributionQueryLog::getQueryId).toList()));
            pending.setFactorIds(JSON.toJSONString(factorIdsByNode(task.getTaskId(), pending.getNodeId())));
            store.updateNode(pending);
            int depth = pending.getLayer();
            task.setDrillDepth(depth);
            appendDrillPath(task, step, pending);
            task.setStatus(AttributionTaskStatus.DRILLING.name());
            task.setReviewState(ReviewState.NONE.name());
            store.updateTask(task);
            // 继续后续维度下钻
            List<String> drilled = parseDrilledDimensions(task.getDrillPathJson());
            List<DrillPlanStep> nextSteps = drillPlanner.plan(null, snapshot,
                    cfg.getMaxDepth() - depth, drilled);
            List<RcagentAttributionNode> lastNodes = store.listNodesByTask(task.getTaskId());
            RcagentAttributionNode lastNode = lastNodes.isEmpty() ? null : lastNodes.get(lastNodes.size() - 1);
            return runDrillLoop(task, snapshot, timeRange, anomaly, cfg, null, nextSteps, lastNode);
        }
        // 无 REVIEWING 节点：直接走结论
        return conclude(task, snapshot, anomaly, cfg, effectiveOperator(null));
    }

    private AttributionResultVO rejectAndSkipQueryLayer(RcagentAttributionTask task, AttributionReviewDecision decision) {
        // 跳过该层：软删该层 node/factor/query，回到 DRILLING 以继续其他候选维度或直接结论
        List<RcagentAttributionNode> nodes = store.listNodesByTask(task.getTaskId());
        RcagentAttributionNode pending = null;
        for (RcagentAttributionNode n : nodes) {
            if ("REVIEWING".equals(n.getDrillStatus())) {
                pending = n;
                break;
            }
        }
        if (pending != null) {
            store.softDeleteFactorsByNode(task.getTaskId(), pending.getNodeId());
            store.softDeleteQueryLogsByNode(task.getTaskId(), pending.getNodeId());
            store.softDeleteNode(task.getTaskId(), pending.getNodeId());
        }
        task.setStatus(AttributionTaskStatus.DRILLING.name());
        task.setReviewState(ReviewState.NONE.name());
        store.updateTask(task);
        MetricKnowledgeSnapshot snapshot = MetricKnowledgeSnapshot.fromJson(task.getMetricKnowledgeJson());
        TimeRange timeRange = parseTimeRange(task.getTimeRangeJson());
        AnomalyResult anomaly = parseAnomaly(task.getAnomalyJson());
        ConfigOverride.EffectiveConfig cfg = resolveConfig(null);
        List<String> drilled = parseDrilledDimensions(task.getDrillPathJson());
        List<RcagentAttributionNode> nodes2 = store.listNodesByTask(task.getTaskId());
        RcagentAttributionNode lastNode = nodes2.isEmpty() ? null : nodes2.get(nodes2.size() - 1);
        List<DrillPlanStep> next = drillPlanner.plan(null, snapshot, cfg.getMaxDepth() - task.getDrillDepth(), drilled);
        return runDrillLoop(task, snapshot, timeRange, anomaly, cfg, decision, next, lastNode);
    }

    private AttributionResultVO decideConclusionReview(RcagentAttributionTask task, AttributionReviewDecision decision,
                                                       String decisionValue, Long operatorId, int round) {
        if (!ReviewState.CONCLUSION_REVIEWING.name().equals(task.getReviewState())
                && !AttributionTaskStatus.CONCLUSION_REVIEWING.name().equals(task.getStatus())) {
            throw new BusinessException(AttributionErrorCode.REVIEW_IDEMPOTENT_CONFLICT,
                    "该归因任务不处于 CONCLUSION_REVIEWING，禁止审核（状态冲突/重复审核）");
        }
        RcagentAttributionReviewLog reviewLog = new RcagentAttributionReviewLog();
        reviewLog.setReviewId(store.nextReviewId());
        reviewLog.setTaskId(task.getTaskId());
        reviewLog.setThreadId(task.getThreadId());
        reviewLog.setReviewObject(ReviewObject.CONCLUSION.name());
        reviewLog.setDecision(decisionValue);
        reviewLog.setComment(truncate(decision.getComment(), 1000));
        reviewLog.setReviewerId(operatorId);
        reviewLog.setReviewRound(round);
        reviewLog.setIsDeleted(0);
        store.insertReviewLog(reviewLog);

        ConfigOverride.EffectiveConfig cfg = resolveConfig(null);
        MetricKnowledgeSnapshot snapshot = MetricKnowledgeSnapshot.fromJson(task.getMetricKnowledgeJson());
        AnomalyResult anomaly = parseAnomaly(task.getAnomalyJson());

        if (com.agenthub.ai.rcagent.attribution.constant.ReviewDecision.APPROVE.name().equals(decisionValue)) {
            task.setStatus(AttributionTaskStatus.FINISHED.name());
            task.setReviewState(ReviewState.NONE.name());
            task.setResultStatus(ResultStatus.CONCLUSION.name());
            store.updateTask(task);
            AttributionResultVO result = buildResultVO(task, true, false);
            result.setStage("DONE");
            result.setConclusion(parseConclusion(task.getConclusionJson()));
            result.setSummary(task.getConclusionSummary());
            return result;
        }
        String action = decision.getActionOnReject() == null ? "DEGRADE" : decision.getActionOnReject();
        int rejectRounds = store.countConclusionRejectRounds(task.getTaskId());
        if ("REWRITE".equalsIgnoreCase(action) && rejectRounds <= MAX_REWRITE_ROUND) {
            // 打回重写：降置信重写并保留原因（review_round 递增），随后重新判定门闸（防循环在 coordinator 依据真实条件）
            ConclusionVO conclusion = parseConclusion(task.getConclusionJson());
            conclusion.setConfidence(BigDecimal.valueOf(
                    Math.min(conclusion.getConfidence() == null ? 0.3 : conclusion.getConfidence().doubleValue() * 0.6, 0.3))
                    .setScale(2, java.math.RoundingMode.HALF_UP));
            if (decision.getComment() != null && !decision.getComment().isBlank()) {
                conclusion.setSensitiveNote("结论曾被人工打回修正：" + SqlTextUtil.truncate(decision.getComment(), 200));
            }
            String summary = conclusionGenerator.summary(conclusion);
            task.setConclusionJson(JSON.toJSONString(conclusion));
            task.setConclusionSummary(summary);
            task.setConfidence(conclusion.getConfidence());
            task.setStatus(AttributionTaskStatus.FINISHED.name());
            task.setReviewState(ReviewState.NONE.name());
            task.setResultStatus(ResultStatus.REJECTED_RETRY.name());
            store.updateTask(task);
            AttributionResultVO result = buildResultVO(task, true, false);
            result.setStage("DONE");
            result.setConclusion(conclusion);
            result.setSummary(summary);
            return result;
        }
        // DEGRADE 或重试超限：降级为仅数据展示
        if (rejectRounds > MAX_REWRITE_ROUND) {
            throw new BusinessException(AttributionErrorCode.REWRITE_LIMIT_REACHED, "结论打回修正超限，请人工复盘口径/下钻路径");
        }
        task.setStatus(AttributionTaskStatus.FINISHED.name());
        task.setReviewState(ReviewState.NONE.name());
        task.setResultStatus(ResultStatus.DEGRADED.name());
        task.setConclusionSummary("结论经人工打回后降级为仅数据展示（DEGRADED）");
        store.updateTask(task);
        AttributionResultVO result = buildResultVO(task, false, false);
        result.setStage("DEGRADED");
        result.setDegraded(degraded(task.getMetricName(), cfg, task, "结论被人工打回，已降级为仅数据展示"));
        return result;
    }

    /** 通用下钻循环（analyze/followUp/审核放行后继续共用），返回中途审核或最终结论结果 */
    private AttributionResultVO runDrillLoop(RcagentAttributionTask task, MetricKnowledgeSnapshot snapshot,
                                             TimeRange timeRange, AnomalyResult anomaly,
                                             ConfigOverride.EffectiveConfig cfg,
                                             AttributionReviewDecision rejectedDecision,
                                             List<DrillPlanStep> steps, RcagentAttributionNode lastNode) {
        int depth = task.getDrillDepth() == null ? 0 : task.getDrillDepth();
        for (DrillPlanStep step : steps) {
            if (step == null || step.getColumn() == null) {
                continue;
            }
            if (task.getQueryCount() + 2 > cfg.getMaxQueries()) {
                break;
            }
            if (depth + 1 > cfg.getMaxDepth()) {
                break;
            }
            int seq = store.nextNodeSeq(task.getTaskId());
            String nodeId = store.nextNodeId(task.getTaskId(), seq);
            int newLayer = depth + 1;
            RcagentAttributionNode node = new RcagentAttributionNode();
            node.setNodeId(nodeId);
            node.setTaskId(task.getTaskId());
            node.setLayer(newLayer);
            node.setDimension(step.getDimension());
            node.setDimensionLabel(step.getDimensionLabel());
            node.setNodeType(NodeType.GROUP_DRILL.name());
            node.setParentNodeId(lastNode == null ? rootNodeId(task.getTaskId()) : lastNode.getNodeId());
            if (step.getFocusSegmentKey() != null) {
                node.setDimensionValue(step.getFocusSegmentKey());
                node.setNodeType(NodeType.CROSS_DRILL.name());
            }
            node.setDrillStatus("REVIEWING");
            node.setSortOrder(newLayer);
            node.setIsDeleted(0);
            store.insertNode(node);
            String focusColumn = step.getFocusSegmentKey() != null && lastNode != null
                    ? snapshot.matchColumn(lastNode.getDimension()) : null;
            QueryOutcome cur = runGuarded(task, nodeId, QueryType.GROUP_CUR.name(),
                    buildGroupSql(snapshot, timeRange, step, focusColumn, true), false, effectiveOperator(null));
            QueryOutcome base = runGuarded(task, nodeId, QueryType.GROUP_BASE.name(),
                    buildGroupSql(snapshot, timeRange, step, focusColumn, false), false, effectiveOperator(null));
            persistQueryLogs(task, List.of(cur, base));

            if (requiresQueryReview(cur) || requiresQueryReview(base)) {
                node.setDrillStatus("REVIEWING");
                store.updateNode(node);
                task.setStatus(AttributionTaskStatus.QUERY_REVIEWING.name());
                task.setReviewState(ReviewState.QUERY_REVIEWING.name());
                store.updateTask(task);
                AttributionResultVO result = buildResultVO(store.findTask(task.getTaskId()), false, false);
                result.setStage("QUERY_REVIEWING");
                result.setPendingReview(pendingReviewOf(store.findTask(task.getTaskId()), null,
                        "归因下钻 SQL 命中 MEDIUM/HIGH，需人工放行（BR-002）"));
                return result;
            }
            if (cur.isBlocked() || base.isBlocked()) {
                node.setDrillStatus("FAILED");
                store.updateNode(node);
                task.setQualityFlagsJson(JSON.toJSONString(mergeFlags(task, true)));
                store.updateTask(task);
                continue;
            }
            DrillNodeSummary summary = computeAndPersistFactors(task, node, step, cur, base, cfg);
            node.setDrillStatus("EXECUTED");
            node.setCompareSummaryJson(JSON.toJSONString(summary));
            node.setQueryIds(JSON.toJSONString(Arrays.asList(cur.getQueryId(), base.getQueryId())));
            node.setFactorIds(JSON.toJSONString(factorIdsByNode(task.getTaskId(), nodeId)));
            store.updateNode(node);
            lastNode = node;
            depth = newLayer;
            task = store.findTask(task.getTaskId());
            task.setDrillDepth(depth);
            appendDrillPath(task, step, node);
            store.updateTask(task);
        }
        task = store.findTask(task.getTaskId());
        return conclude(task, snapshot, anomaly, cfg, effectiveOperator(null));
    }

    // ============================================================
    // Guard 执行 + query_log 落库
    // ============================================================

    private QueryOutcome runGuarded(RcagentAttributionTask task, String nodeId, String queryType,
                                    String sql, boolean requireManualApproval, Long operatorId) {
        QueryOutcome outcome = new QueryOutcome();
        outcome.setQueryId(store.nextQueryId());
        outcome.setQueryType(queryType);
        outcome.setNodeId(nodeId);
        outcome.setDsId(task.getDsId());
        outcome.setSqlText(sql);
        outcome.setSqlAbstract(SqlTextUtil.truncate(SqlTextUtil.abstractSql(sql), SQL_ABSTRACT_MAX));
        guardAdapter.guard(outcome, requireManualApproval);
        return outcome;
    }

    private void persistQueryLogs(RcagentAttributionTask task, List<QueryOutcome> outcomes) {
        if (outcomes == null) {
            return;
        }
        int count = 0;
        for (QueryOutcome outcome : outcomes) {
            if (outcome == null) {
                continue;
            }
            RcagentAttributionQueryLog logEntity = new RcagentAttributionQueryLog();
            logEntity.setQueryId(outcome.getQueryId());
            logEntity.setDsId(outcome.getDsId());
            logEntity.setTaskId(task.getTaskId());
            logEntity.setNodeId(outcome.getNodeId());
            logEntity.setQueryType(outcome.getQueryType());
            logEntity.setSqlText(outcome.getSqlText());
            logEntity.setSqlAbstract(outcome.getSqlAbstract());
            logEntity.setRiskLevel(outcome.getRiskLevel());
            logEntity.setVerdict(outcome.getVerdict());
            logEntity.setReviewStatus(outcome.getReviewStatus() == null
                    ? QueryReviewStatus.NONE.name() : outcome.getReviewStatus());
            logEntity.setExecuted(outcome.isExecuted() ? 1 : 0);
            if (outcome.getResult() != null) {
                logEntity.setResultJson(JSON.toJSONString(resultSnapshot(outcome.getResult())));
                logEntity.setCostMs(outcome.getResult().getCostMs() == null ? null : outcome.getResult().getCostMs().intValue());
            }
            logEntity.setResultCheckJson(outcome.getResultCheckJson());
            logEntity.setFixSuggestion(outcome.getFixSuggestion());
            logEntity.setErrCode(outcome.getErrCode());
            logEntity.setErrMsg(outcome.getErrMsg());
            logEntity.setCostMs(outcome.getCostMs());
            logEntity.setIsDeleted(0);
            store.insertQueryLog(logEntity);
            count++;
        }
        if (count > 0) {
            RcagentAttributionTask latest = store.findTask(task.getTaskId());
            if (latest != null) {
                latest.setQueryCount((latest.getQueryCount() == null ? 0 : latest.getQueryCount()) + count);
                store.updateTask(latest);
            }
        }
    }

    private void updateQueryLogEntity(RcagentAttributionQueryLog logEntity, QueryOutcome outcome) {
        logEntity.setRiskLevel(outcome.getRiskLevel());
        logEntity.setVerdict(outcome.getVerdict());
        logEntity.setExecuted(outcome.isExecuted() ? 1 : 0);
        if (outcome.getResult() != null) {
            logEntity.setResultJson(JSON.toJSONString(resultSnapshot(outcome.getResult())));
            logEntity.setCostMs(outcome.getResult().getCostMs() == null ? null : outcome.getResult().getCostMs().intValue());
        }
        logEntity.setResultCheckJson(outcome.getResultCheckJson());
        logEntity.setFixSuggestion(outcome.getFixSuggestion());
        logEntity.setErrCode(outcome.getErrCode());
        logEntity.setErrMsg(outcome.getErrMsg());
    }

    private com.agenthub.ai.dbaccess.model.QueryResult toQueryResult(RcagentAttributionQueryLog logEntity) {
        if (logEntity.getResultJson() == null || logEntity.getResultJson().isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(logEntity.getResultJson(),
                    com.agenthub.ai.dbaccess.model.QueryResult.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> resultSnapshot(com.agenthub.ai.dbaccess.model.QueryResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("column_names", result.getColumnNames());
        map.put("rows", result.getRows());
        map.put("row_count", result.getRowCount());
        map.put("truncated", result.getTruncated());
        map.put("cost_ms", result.getCostMs());
        map.put("sensitive_masked", result.getSensitiveMasked());
        return map;
    }

    private boolean requiresQueryReview(QueryOutcome outcome) {
        return outcome != null && !outcome.isExecuted() && !outcome.isBlocked()
                && ("MEDIUM".equals(outcome.getRiskLevel()) || "HIGH".equals(outcome.getRiskLevel()));
    }

    // ============================================================
    // 结果/审核 VO 组装
    // ============================================================

    private AttributionResultVO buildResultVO(RcagentAttributionTask task, boolean withDetail, boolean includeSql) {
        AttributionResultVO vo = new AttributionResultVO();
        if (task == null) {
            return vo;
        }
        AttributionTaskVO taskVO = AttributionTaskVO.from(task, AttributionTaskStore.anomalyDirection(task.getAnomalyJson()));
        vo.setTask(taskVO);
        String status = task.getStatus();
        if (AttributionTaskStatus.FINISHED.name().equals(status)) {
            vo.setStage("DONE");
        } else if (AttributionTaskStatus.QUERY_REVIEWING.name().equals(status)) {
            vo.setStage("QUERY_REVIEWING");
        } else if (AttributionTaskStatus.CONCLUSION_REVIEWING.name().equals(status)) {
            vo.setStage("CONCLUSION_REVIEWING");
        } else if (AttributionTaskStatus.CANCELLED.name().equals(status)) {
            vo.setStage("DONE");
        } else {
            vo.setStage(status);
        }
        vo.setAnomaly(toAnomalyVO(parseAnomaly(task.getAnomalyJson())));
        if (task.getConclusionSummary() != null) {
            vo.setSummary(task.getConclusionSummary());
        }
        vo.setConclusion(parseConclusion(task.getConclusionJson()));

        if (!withDetail) {
            return vo;
        }
        List<RcagentAttributionNode> nodes = store.listNodesByTask(task.getTaskId());
        List<RcagentAttributionFactor> factors = store.listFactorsByTask(task.getTaskId());
        List<RcagentAttributionQueryLog> queryLogs = store.listQueryLogsByTask(task.getTaskId());
        Map<String, RcagentAttributionQueryLog> logIndex = queryLogs.stream()
                .collect(Collectors.toMap(RcagentAttributionQueryLog::getQueryId, e -> e));
        Map<String, List<RcagentAttributionFactor>> factorByNode = new LinkedHashMap<>();
        for (RcagentAttributionFactor factor : factors) {
            factorByNode.computeIfAbsent(factor.getNodeId(), k -> new ArrayList<>()).add(factor);
        }
        List<AttributionNodeVO> drillTree = new ArrayList<>();
        for (RcagentAttributionNode node : nodes) {
            if (node == null || NodeType.ROOT.name().equals(node.getNodeType())) {
                continue;
            }
            AttributionNodeVO nodeVO = AttributionNodeVO.from(node);
            nodeVO.setQueryIds(parseIds(node.getQueryIds()));
            nodeVO.setFactorIds(parseIds(node.getFactorIds()));
            nodeVO.setCompareSummary(parseNodeSummary(node.getCompareSummaryJson()));
            List<RcagentAttributionFactor> nodeFactors = factorByNode.get(node.getNodeId());
            if (nodeFactors != null) {
                nodeVO.setFactors(nodeFactors.stream()
                        .sorted(Comparator.comparing(RcagentAttributionFactor::getRank))
                        .map(AttributionFactorVO::from).collect(Collectors.toList()));
            }
            drillTree.add(nodeVO);
        }
        vo.setDrillTree(drillTree);
        vo.setFactors(factors.stream().map(AttributionFactorVO::from).collect(Collectors.toList()));
        vo.setMainFactors(factors.stream()
                .filter(f -> FactorStatus.MAIN_FACTOR.name().equals(f.getStatus())
                        || FactorStatus.SECONDARY.name().equals(f.getStatus()))
                .map(AttributionFactorVO::from).collect(Collectors.toList()));
        if (withDetail) {
            List<AttributionQueryVO> queries = new ArrayList<>();
            for (RcagentAttributionQueryLog logEntity : queryLogs) {
                AttributionQueryVO q = AttributionQueryVO.from(logEntity);
                if (!includeSql) {
                    q.setSqlText(null); // 受控字段：默认不回传 SQL 原文（AGENTS.md 5.x）
                }
                queries.add(q);
            }
            vo.setQueries(queries);
        }
        return vo;
    }

    private PendingReviewVO pendingReviewOf(RcagentAttributionTask task, ReviewCoordinator.ConclusionReviewReason reason,
                                            String defaultReason) {
        PendingReviewVO vo = new PendingReviewVO();
        List<PendingReviewVO.PendingReviewItem> items = new ArrayList<>();
        if (ReviewState.QUERY_REVIEWING.name().equals(task.getReviewState())) {
            List<RcagentAttributionQueryLog> pendingQueries = store.listQueryLogsByTask(task.getTaskId()).stream()
                    .filter(q -> QueryReviewStatus.NONE.name().equals(q.getReviewStatus()))
                    .collect(Collectors.toList());
            for (RcagentAttributionQueryLog q : pendingQueries) {
                PendingReviewVO.PendingReviewItem item = new PendingReviewVO.PendingReviewItem();
                item.setType(ReviewObject.QUERY.name());
                item.setTaskId(task.getTaskId());
                item.setQueryId(q.getQueryId());
                item.setQueryType(q.getQueryType());
                item.setSqlAbstract(q.getSqlAbstract());
                item.setRiskLevel(q.getRiskLevel());
                item.setReason("归因查询风险 " + q.getRiskLevel() + "，需人工放行");
                items.add(item);
            }
            vo.setReason(defaultReason == null ? "归因下钻 SQL 命中 MEDIUM/HIGH，需人工放行" : defaultReason);
        } else if (ReviewState.CONCLUSION_REVIEWING.name().equals(task.getReviewState())) {
            PendingReviewVO.PendingReviewItem item = new PendingReviewVO.PendingReviewItem();
            item.setType(ReviewObject.CONCLUSION.name());
            item.setTaskId(task.getTaskId());
            item.setQueryId(task.getTaskId());
            item.setConclusionSummary(task.getConclusionSummary());
            item.setReason(reason == null ? "归因结论需人工确认" : reason.getReasonText());
            items.add(item);
            vo.setReason(reason == null ? "归因结论需人工确认" : reason.getReasonText());
        }
        vo.setItems(items);
        return vo;
    }

    // ============================================================
    // 校验与模型解析辅助
    // ============================================================

    private void validateAnalyzeRequest(AttributionAnalyzeRequest request) {
        if (request == null) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "请求参数不能为空");
        }
        ThrowUtils.throwIf(request.getDsId() == null || request.getDsId().isBlank(),
                new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "ds_id 不能为空"));
        ThrowUtils.throwIf(!request.getDsId().matches(DS_ID_PATTERN),
                new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "ds_id 格式非法"));
        ThrowUtils.throwIf(request.getDsId().length() > 64,
                new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "ds_id 过长"));
        ThrowUtils.throwIf(request.getMetricName() == null || request.getMetricName().isBlank(),
                new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "指标名不能为空"));
        ThrowUtils.throwIf(request.getMetricName().length() > 128,
                new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "指标名过长"));
        requireDate(request.getCurrentStart(), "current_start 不能为空");
        requireDate(request.getCurrentEnd(), "current_end 不能为空");
        requireDate(request.getBaseStart(), "base_start 不能为空");
        requireDate(request.getBaseEnd(), "base_end 不能为空");
        if (request.getCurrentStart().compareTo(request.getCurrentEnd()) > 0) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "本期时间范围非法");
        }
        if (request.getBaseStart().compareTo(request.getBaseEnd()) > 0) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "基期时间范围非法");
        }
        if (request.getCompareType() != null && !CompareType.isValid(request.getCompareType())) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "compare_type 非法");
        }
        if (request.getTriggerType() != null && !com.agenthub.ai.rcagent.attribution.constant.TriggerType.isValid(request.getTriggerType())) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "trigger_type 非法");
        }
        if (request.getDimensions() != null) {
            for (String dimension : request.getDimensions()) {
                if (dimension == null || dimension.isBlank() || dimension.length() > 128) {
                    throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "归因维度非法（每项 ≤128）");
                }
            }
        }
    }

    private void validateDecision(AttributionReviewDecision decision) {
        if (decision == null) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "请求参数不能为空");
        }
        if (decision.getTaskId() == null || decision.getTaskId().isBlank()) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "task_id 不能为空");
        }
        if (decision.getReviewObject() == null || !ReviewObject.isValid(decision.getReviewObject())) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "review_object 非法");
        }
        if (decision.getDecision() == null || !com.agenthub.ai.rcagent.attribution.constant.ReviewDecision.isValid(decision.getDecision())) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "decision 非法");
        }
        if (com.agenthub.ai.rcagent.attribution.constant.ReviewDecision.REJECT.name().equals(decision.getDecision())
                && (decision.getComment() == null || decision.getComment().isBlank())) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "打回必须附审核意见");
        }
        if (decision.getComment() != null && decision.getComment().length() > 1000) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, "审核意见过长（≤1000）");
        }
    }

    private void requireDate(String date, String message) {
        if (date == null || date.isBlank()) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, message);
        }
        if (!date.trim().matches(DATE_PATTERN)) {
            throw new BusinessException(AttributionErrorCode.PARAM_ILLEGAL, message + "（格式 YYYY-MM-DD）");
        }
    }

    private ConfigOverride.EffectiveConfig resolveConfig(ConfigOverride overrides) {
        return (overrides == null ? new ConfigOverride() : overrides).apply(properties);
    }

    private Long effectiveOperator(Long operatorId) {
        if (operatorId != null) {
            return operatorId;
        }
        Long current = BaseContext.getCurrentId();
        return current == null ? 0L : current;
    }

    private TimeRange buildTimeRange(AttributionAnalyzeRequest request) {
        TimeRange range = new TimeRange();
        range.setCompareType(request.getCompareType() == null ? CompareType.MOM.name() : request.getCompareType());
        range.getCurrent().setStart(request.getCurrentStart());
        range.getCurrent().setEnd(request.getCurrentEnd());
        range.getCurrent().setGranularity("day");
        range.getBase().setStart(request.getBaseStart());
        range.getBase().setEnd(request.getBaseEnd());
        range.getBase().setGranularity("day");
        return range;
    }

    private TimeRange parseTimeRange(String json) {
        if (json == null || json.isBlank()) {
            return new TimeRange();
        }
        try {
            TimeRange range = JSON.parseObject(json, TimeRange.class);
            return range == null ? new TimeRange() : range;
        } catch (Exception e) {
            return new TimeRange();
        }
    }

    private AnomalyResult parseAnomaly(String json) {
        if (json == null || json.isBlank()) {
            return new AnomalyResult();
        }
        try {
            AnomalyResult result = JSON.parseObject(json, AnomalyResult.class);
            return result == null ? new AnomalyResult() : result;
        } catch (Exception e) {
            return new AnomalyResult();
        }
    }

    private ConclusionVO parseConclusion(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json, ConclusionVO.class);
        } catch (Exception e) {
            return null;
        }
    }

    private AnomalyVO toAnomalyVO(AnomalyResult anomaly) {
        if (anomaly == null) {
            return null;
        }
        AnomalyVO vo = new AnomalyVO();
        vo.setAnomaly(anomaly.isAnomaly());
        vo.setDirection(anomaly.getDirection());
        vo.setCurTotal(anomaly.getCurTotal());
        vo.setBaseTotal(anomaly.getBaseTotal());
        vo.setRateMom(anomaly.getRateMom());
        vo.setRateYoy(anomaly.getRateYoy());
        vo.setSeverity(anomaly.getSeverity());
        vo.setContinuityRatio(anomaly.getContinuityRatio());
        vo.setPseudoFlags(anomaly.getPseudoFlags());
        vo.setRulesHit(anomaly.getRulesHit());
        vo.setMessage(anomaly.getMessage());
        return vo;
    }

    private BigDecimal rateOf(BigDecimal cur, BigDecimal base) {
        if (base == null || base.signum() == 0 || cur == null) {
            return null;
        }
        return cur.subtract(base).multiply(new BigDecimal("100")).divide(base, 2, java.math.RoundingMode.HALF_UP);
    }

    private boolean isSensitiveTask(RcagentAttributionTask task, List<RcagentAttributionFactor> factors,
                                    MetricKnowledgeSnapshot snapshot) {
        if (factors == null) {
            return false;
        }
        for (RcagentAttributionFactor factor : factors) {
            if (factor != null && Integer.valueOf(1).equals(factor.getSensitiveFlag())) {
                return true;
            }
        }
        return false;
    }

    private List<String> factorIdsByNode(String taskId, String nodeId) {
        return store.listFactorsByNode(nodeId).stream()
                .map(RcagentAttributionFactor::getFactorId).collect(Collectors.toList());
    }

    private List<String> parseIds(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private DrillNodeSummaryVO parseNodeSummary(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.parseObject(json, DrillNodeSummaryVO.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String rootNodeId(String taskId) {
        List<RcagentAttributionNode> nodes = store.listNodesByTask(taskId);
        for (RcagentAttributionNode n : nodes) {
            if (NodeType.ROOT.name().equals(n.getNodeType())) {
                return n.getNodeId();
            }
        }
        return null;
    }

    private void appendDrillPath(RcagentAttributionTask task, DrillPlanStep step, RcagentAttributionNode node) {
        List<Map<String, String>> path = parseDrillPath(task.getDrillPathJson());
        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("dimension", step.getDimension());
        entry.put("segment_key", step.getFocusSegmentKey() == null ? "" : step.getFocusSegmentKey());
        entry.put("node_id", node.getNodeId());
        path.add(entry);
        task.setDrillPathJson(JSON.toJSONString(path));
    }

    private List<Map<String, String>> parseDrillPath(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, String>> list = new ArrayList<>();
            for (Object item : JSON.parseArray(json)) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    map.forEach((k, v) -> entry.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
                    list.add(entry);
                }
            }
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<String> parseDrilledDimensions(String drillPathJson) {
        List<String> dimensions = new ArrayList<>();
        for (Map<String, String> entry : parseDrillPath(drillPathJson)) {
            if (entry != null && entry.get("dimension") != null && !entry.get("dimension").isBlank()) {
                dimensions.add(entry.get("dimension"));
            }
        }
        return dimensions;
    }

    private QualityFlags mergeFlags(RcagentAttributionTask task, boolean sqlBlocked) {
        QualityFlags flags = new QualityFlags();
        try {
            flags = JSON.parseObject(task.getQualityFlagsJson(), QualityFlags.class);
        } catch (Exception ignored) {
            // 忽略解析失败，用新信号
        }
        if (flags == null) {
            flags = new QualityFlags();
        }
        flags.setSqlBlocked(flags.isSqlBlocked() || sqlBlocked);
        return flags;
    }

    private List<AttributionFactorVO> factorVOs(List<RcagentAttributionFactor> factors) {
        return factors.stream().map(AttributionFactorVO::from).collect(Collectors.toList());
    }

    private List<AttributionFactorVO> mainFactorVOs(List<RcagentAttributionFactor> factors) {
        return factors.stream()
                .filter(f -> FactorStatus.MAIN_FACTOR.name().equals(f.getStatus())
                        || FactorStatus.SECONDARY.name().equals(f.getStatus()))
                .map(AttributionFactorVO::from).collect(Collectors.toList());
    }

    private AttributionResultVO degradedResult(RcagentAttributionTask task, ConfigOverride.EffectiveConfig cfg,
                                               String metric, MetricKnowledgeSnapshot snapshot, TimeRange timeRange,
                                               String reason, boolean verified) {
        AttributionResultVO result = buildResultVO(task, false, false);
        result.setStage("DEGRADED");
        result.setDegraded(degraded(metric, cfg, task, reason));
        return result;
    }

    private AttributionResultVO.DegradedVO degraded(String metric, ConfigOverride.EffectiveConfig cfg,
                                                    RcagentAttributionTask task, String reason) {
        AttributionResultVO.DegradedVO degraded = new AttributionResultVO.DegradedVO();
        degraded.setMetricDefined(Integer.valueOf(1).equals(task.getMetricDefinitionVerified()));
        degraded.setReason(reason);
        degraded.setDepthReached(task.getDrillDepth());
        degraded.setQueriesUsed(task.getQueryCount());
        return degraded;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int reviewRoundOf(String taskId) {
        List<RcagentAttributionReviewLog> logs = store.listReviewLogsByTask(taskId);
        int maxRound = 0;
        for (RcagentAttributionReviewLog r : logs) {
            if (r.getReviewRound() != null && r.getReviewRound() > maxRound) {
                maxRound = r.getReviewRound();
            }
        }
        return maxRound;
    }

    private QueryOutcome toOutcomeFromLog(List<RcagentAttributionQueryLog> logs, String queryType) {
        for (RcagentAttributionQueryLog logEntity : logs) {
            if (queryType.equals(logEntity.getQueryType())) {
                QueryOutcome outcome = new QueryOutcome();
                outcome.setQueryId(logEntity.getQueryId());
                outcome.setQueryType(logEntity.getQueryType());
                outcome.setNodeId(logEntity.getNodeId());
                outcome.setDsId(logEntity.getDsId());
                outcome.setExecuted(Integer.valueOf(1).equals(logEntity.getExecuted()));
                outcome.setResult(toQueryResult(logEntity));
                return outcome;
            }
        }
        return null;
    }
}
