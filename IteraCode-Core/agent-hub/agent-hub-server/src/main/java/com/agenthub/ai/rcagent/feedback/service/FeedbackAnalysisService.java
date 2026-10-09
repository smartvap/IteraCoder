package com.agenthub.ai.rcagent.feedback.service;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.feedback.adapter.DsConfigResolver;
import com.agenthub.ai.rcagent.feedback.adapter.KbFeedbackAdapter;
import com.agenthub.ai.rcagent.feedback.adapter.ReviewTaskAdapter;
import com.agenthub.ai.rcagent.feedback.analyzer.ChangeTypeClassifier;
import com.agenthub.ai.rcagent.feedback.analyzer.ChangeTypeResult;
import com.agenthub.ai.rcagent.feedback.analyzer.DiffSummary;
import com.agenthub.ai.rcagent.feedback.analyzer.KnowledgeBuilder;
import com.agenthub.ai.rcagent.feedback.analyzer.SqlDiffAnalyzer;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackAnalyzeStatus;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackErrorCode;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackStatus;
import com.agenthub.ai.rcagent.feedback.dto.FeedbackSubmitRequest;
import com.agenthub.ai.rcagent.feedback.dto.KnowledgeRef;
import com.agenthub.ai.rcagent.feedback.entity.FeedbackFlow;
import com.agenthub.ai.rcagent.feedback.mapper.FeedbackFlowMapper;
import com.agenthub.ai.rcagent.feedback.model.KnowledgeSuggestion;
import com.agenthub.ai.rcagent.feedback.util.FeedbackJsonUtil;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.rcagent.kb.vo.KbFeedbackVO;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 反馈分析管线（接口5 analyze：diff → 分类 → 建议 → 逐条 review/task/create → UPDATE flow）。
 *
 * <p>提交反馈后异步自动执行，也可对 ANALYZE_FAILED/MANUAL_REQUIRED 流手动重跑。
 * diff 无法解析（无修正 SQL/语法错误）置 MANUAL_REQUIRED 转人工，不自动建审/改知识
 * （BR-001/BR-002）。POSITIVE 反馈无知识修改，直接置 CLOSED（正面置信度信号在提交阶段推进）。</p>
 *
 * <p>上下文来源：提交场景携带完整 {@link FeedbackSubmitRequest}（attributionContext 弱类型
 * 快照可参与归因分类）；手动重跑场景经 module-004 kb_feedback 回溯（attributionContext 未
 * 落库，归因依赖文本/LLM 兜底，属契约声明扩展点）。</p>
 */
@Slf4j
@Service
public class FeedbackAnalysisService {

    private final FeedbackFlowMapper flowMapper;
    private final KbFeedbackAdapter kbFeedbackAdapter;
    private final DsConfigResolver dsConfigResolver;
    private final SqlDiffAnalyzer sqlDiffAnalyzer;
    private final ChangeTypeClassifier changeTypeClassifier;
    private final KnowledgeBuilder knowledgeBuilder;
    private final ReviewTaskAdapter reviewTaskAdapter;

    public FeedbackAnalysisService(FeedbackFlowMapper flowMapper,
                                   KbFeedbackAdapter kbFeedbackAdapter,
                                   DsConfigResolver dsConfigResolver,
                                   SqlDiffAnalyzer sqlDiffAnalyzer,
                                   ChangeTypeClassifier changeTypeClassifier,
                                   KnowledgeBuilder knowledgeBuilder,
                                   ReviewTaskAdapter reviewTaskAdapter) {
        this.flowMapper = flowMapper;
        this.kbFeedbackAdapter = kbFeedbackAdapter;
        this.dsConfigResolver = dsConfigResolver;
        this.sqlDiffAnalyzer = sqlDiffAnalyzer;
        this.changeTypeClassifier = changeTypeClassifier;
        this.knowledgeBuilder = knowledgeBuilder;
        this.reviewTaskAdapter = reviewTaskAdapter;
    }

    /** 提交场景：携带完整提交上下文（含 attributionContext/relatedKnowledge） */
    public FeedbackFlow analyzeWithContext(Long flowId, FeedbackSubmitRequest request) {
        if (request == null) {
            return analyze(flowId);
        }
        String attributionJson = request.getAttributionContext() == null
                ? null : FeedbackJsonUtil.toJson(request.getAttributionContext());
        return doAnalyze(flowId, request.getQuestion(), request.getGeneratedSql(),
                request.getModifiedSql(), request.getUserComment(), request.getFeedbackType(),
                request.getRelatedKnowledge(), attributionJson);
    }

    /** 手动/重跑场景：经 module-004 kb_feedback 回溯（attribution 不可得，文本/LLM 兜底） */
    public FeedbackFlow analyze(Long flowId) {
        FeedbackFlow flow = requireFlow(flowId);
        KbFeedbackVO feedback = kbFeedbackAdapter.findByIdentifier(
                flow.getDsId(), flow.getSessionId(), flow.getFeedbackId());
        if (feedback == null) {
            fail(flow, "kb_feedback 不存在，无法分析");
            return flow;
        }
        return doAnalyze(flowId, feedback.getQuestion(), feedback.getGeneratedSql(),
                feedback.getModifiedSql(), feedback.getUserComment(), feedback.getFeedbackType(),
                null, null);
    }

    /** 分析主流程（幂等/状态/失败保护统一收敛） */
    private FeedbackFlow doAnalyze(Long flowId,
                                   String question, String generatedSql, String modifiedSql,
                                   String userComment, String feedbackType,
                                   List<KnowledgeRef> relatedKnowledge, String attributionJson) {
        FeedbackFlow flow = requireFlow(flowId);
        String analyzeStatus = flow.getAnalyzeStatus() == null ? "" : flow.getAnalyzeStatus();
        if (FeedbackAnalyzeStatus.REVIEW_CREATED.getValue().equals(analyzeStatus)
                || FeedbackAnalyzeStatus.DIFF_ANALYZING.getValue().equals(analyzeStatus)
                || FeedbackAnalyzeStatus.SUGGESTING.getValue().equals(analyzeStatus)) {
            // 已建审或分析进行中（DIFF_ANALYZING/SUGGESTING 防并发重入，契约幂等表 analyze 并发 → 45008）
            throw new BusinessException(FeedbackErrorCode.IDEMPOTENT_CONFLICT,
                    "分析已在进行或已完成审核任务创建，请勿重复触发（幂等冲突）");
        }
        String status = flow.getStatus() == null ? "" : flow.getStatus();
        if (FeedbackStatus.CLOSED.getValue().equals(status)
                || FeedbackStatus.REJECTED.getValue().equals(status)) {
            throw new BusinessException(FeedbackErrorCode.STATUS_NOT_ALLOWED, "当前状态不允许重跑分析");
        }

        // 状态抢占（CAS）：并发下两条 analyze 管线同时读到 RECEIVED/ANALYZE_FAILED 时，
        // 仅先执行 UPDATE 命中（analyze_status ∈ 可进入集合）者获得执行权，另一方返回 45008，
        // 避免双管线重复生成建议与重复审核任务
        flow.setStatus(FeedbackStatus.ANALYZING.getValue());
        flow.setAnalyzeStatus(FeedbackAnalyzeStatus.DIFF_ANALYZING.getValue());
        flow.setErrorMsg(null);
        flow.setUpdateTime(new Date());
        int claimed = flowMapper.update(flow, new UpdateWrapper<FeedbackFlow>()
                .eq("id", flow.getId())
                .in("analyze_status",
                        FeedbackAnalyzeStatus.RECEIVED.getValue(),
                        FeedbackAnalyzeStatus.MANUAL_REQUIRED.getValue(),
                        FeedbackAnalyzeStatus.ANALYZE_FAILED.getValue()));
        if (claimed == 0) {
            // 其它请求已抢占/状态已变化：幂等冲突，不做重入
            throw new BusinessException(FeedbackErrorCode.IDEMPOTENT_CONFLICT,
                    "分析已被其它请求触发，请勿重复操作（幂等冲突）");
        }

        try {
            // POSITIVE：无知识修改，直接闭环
            if ("POSITIVE".equalsIgnoreCase(feedbackType)) {
                finishNoChange(flow, "肯定反馈，无需知识修改");
                return flow;
            }

            String dbType = dsConfigResolver.resolveDbType(flow.getDsId());

            // AST diff（无修正 SQL 视为无结构 diff，由 classifier 文本提示兜底）
            DiffSummary diff;
            if (modifiedSql != null && !modifiedSql.isBlank()) {
                diff = sqlDiffAnalyzer.analyze(generatedSql, modifiedSql, dbType);
            } else {
                diff = new DiffSummary();
            }

            ChangeTypeResult classify = changeTypeClassifier.classify(
                    feedbackType, diff, question, userComment, attributionJson);

            if (classify.isManualRequired()) {
                flow.setAnalyzeStatus(FeedbackAnalyzeStatus.MANUAL_REQUIRED.getValue());
                flow.setErrorMsg(FeedbackSafeText.truncate(classify.getReason(), 200));
                flow.setUpdateTime(new Date());
                flowMapper.updateById(flow);
                return flow;
            }

            flow.setChangeType(classify.getChangeType());
            flow.setChangeConfidence(classify.getConfidence());
            flow.setAnalyzeStatus(FeedbackAnalyzeStatus.SUGGESTING.getValue());
            flowMapper.updateById(flow);

            List<KnowledgeSuggestion> suggestions = knowledgeBuilder.build(
                    flow.getDsId(), question, generatedSql, modifiedSql,
                    userComment, feedbackType, classify.getChangeType(),
                    relatedKnowledge, diff, diffEvidence(flow, classify));

            if (suggestions.isEmpty()) {
                flow.setAnalyzeStatus(FeedbackAnalyzeStatus.MANUAL_REQUIRED.getValue());
                flow.setErrorMsg("无自动建议，需人工判定修改类型或目标");
                flow.setUpdateTime(new Date());
                flowMapper.updateById(flow);
                return flow;
            }

            List<Long> taskIds = createReviewTasks(flow, suggestions);
            if (taskIds.isEmpty()) {
                flow.setAnalyzeStatus(FeedbackAnalyzeStatus.ANALYZE_FAILED.getValue());
                flow.setStatus(FeedbackStatus.FAILED.getValue());
                flow.setErrorMsg("审核任务创建失败");
                flow.setUpdateTime(new Date());
                flowMapper.updateById(flow);
                return flow;
            }

            flow.setReviewTaskIds(FeedbackJsonUtil.toJson(taskIds));
            flow.setSuggestions(FeedbackJsonUtil.toJson(suggestions));
            flow.setDiffSummary(diff == null ? null : FeedbackJsonUtil.toJson(diff));
            flow.setAnalyzeStatus(FeedbackAnalyzeStatus.REVIEW_CREATED.getValue());
            flow.setStatus(FeedbackStatus.REVIEWING.getValue());
            flow.setWikiWritebackFlag(hasWikiTarget(suggestions) ? 1 : 0);
            flow.setUpdateTime(new Date());
            flowMapper.updateById(flow);
            return flow;
        } catch (Exception e) {
            log.warn("feedback: 分析管线异常 flowId={}（已置 FAILED 可重试）: {}",
                    flowId, e.getMessage());
            fail(flow, FeedbackSafeText.truncate(e.getMessage(), 200));
            return flow;
        }
    }

    private void fail(FeedbackFlow flow, String reason) {
        flow.setAnalyzeStatus(FeedbackAnalyzeStatus.ANALYZE_FAILED.getValue());
        flow.setStatus(FeedbackStatus.FAILED.getValue());
        flow.setErrorMsg(reason);
        flow.setRetryCount((flow.getRetryCount() == null ? 0 : flow.getRetryCount()) + 1);
        flow.setUpdateTime(new Date());
        flowMapper.updateById(flow);
    }

    /**
     * 逐条建议创建审核任务（供 analyze/classify 复用）。
     * 单条失败仅跳过该建议，不阻断其余（suggestions 快照保留供 writeback 读取）。
     */
    public List<Long> createReviewTasks(FeedbackFlow flow, List<KnowledgeSuggestion> suggestions) {
        List<Long> taskIds = new ArrayList<>();
        if (suggestions == null) {
            return taskIds;
        }
        for (KnowledgeSuggestion suggestion : suggestions) {
            try {
                Long taskId = createSingleReviewTask(flow, suggestion);
                if (taskId != null) {
                    taskIds.add(taskId);
                }
            } catch (Exception e) {
                log.warn("feedback: 建议建审失败 flowId={} targetType={}: {}",
                        flow.getId(), suggestion.getTargetObjectType(), e.getMessage());
            }
        }
        return taskIds;
    }

    /** 单条建议 → module-004 审核任务 */
    public Long createSingleReviewTask(FeedbackFlow flow, KnowledgeSuggestion suggestion) {
        if (suggestion == null || !suggestion.actionable()) {
            return null;
        }
        String evidence = "feedback#" + flow.getId() + ";" + suggestion.getEvidence();
        String targetJson = ReviewTaskAdapter.targetObjectJson(
                suggestion.getTargetObjectId(), suggestion.getTargetDesc());
        return reviewTaskAdapter.createTask(
                flow.getDsId(),
                suggestion.getTaskType() == null ? "FEEDBACK_CORRECTION" : suggestion.getTaskType(),
                suggestion.getTargetObjectType(),
                targetJson,
                suggestion.getSuggestedChange(),
                evidence);
    }

    private boolean hasWikiTarget(List<KnowledgeSuggestion> suggestions) {
        if (suggestions == null) {
            return false;
        }
        for (KnowledgeSuggestion s : suggestions) {
            if (s != null && s.getRepo() != null && s.getRepo().contains("B")) {
                return true;
            }
        }
        return false;
    }

    private void finishNoChange(FeedbackFlow flow, String message) {
        flow.setAnalyzeStatus(FeedbackAnalyzeStatus.REVIEW_CREATED.getValue());
        flow.setStatus(FeedbackStatus.CLOSED.getValue());
        flow.setSuggestions("[]");
        flow.setDiffSummary("{}");
        flow.setErrorMsg(message);
        flow.setUpdateTime(new Date());
        flowMapper.updateById(flow);
    }

    private String diffEvidence(FeedbackFlow flow, ChangeTypeResult classify) {
        return "diff;type=" + classify.getChangeType();
    }

    public FeedbackFlow requireFlow(Long flowId) {
        FeedbackFlow flow = flowMapper.selectById(flowId);
        if (flow == null) {
            throw new BusinessException(FeedbackErrorCode.FLOW_NOT_EXIST, "反馈处理流不存在: flowId=" + flowId);
        }
        return flow;
    }
}
