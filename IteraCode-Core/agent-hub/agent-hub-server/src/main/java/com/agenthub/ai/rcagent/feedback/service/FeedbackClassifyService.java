package com.agenthub.ai.rcagent.feedback.service;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.feedback.adapter.KbFeedbackAdapter;
import com.agenthub.ai.rcagent.feedback.analyzer.KnowledgeBuilder;
import com.agenthub.ai.rcagent.feedback.constant.ChangeType;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackAnalyzeStatus;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackErrorCode;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackStatus;
import com.agenthub.ai.rcagent.feedback.dto.ClassifyRequest;
import com.agenthub.ai.rcagent.feedback.entity.FeedbackFlow;
import com.agenthub.ai.rcagent.feedback.mapper.FeedbackFlowMapper;
import com.agenthub.ai.rcagent.feedback.model.KnowledgeSuggestion;
import com.agenthub.ai.rcagent.feedback.util.FeedbackJsonUtil;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackClassifyVO;
import com.agenthub.ai.rcagent.kb.vo.KbFeedbackVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 人工判定修改类型（接口6 classify）。
 *
 * <p>仅 analyze_status=MANUAL_REQUIRED 可执行一次（FR-002 异常兜底）；人工指定
 * change_type 后进入 KnowledgeBuilder 建议生成，同样受 module-004 人工审核约束
 * （BR-001，不直接改知识）。成功后 analyze_status 置 SUGGESTING/REVIEW_CREATED。</p>
 */
@Slf4j
@Service
public class FeedbackClassifyService {

    private final FeedbackFlowMapper flowMapper;
    private final KbFeedbackAdapter kbFeedbackAdapter;
    private final KnowledgeBuilder knowledgeBuilder;
    private final FeedbackAnalysisService analysisService;

    public FeedbackClassifyService(FeedbackFlowMapper flowMapper,
                                   KbFeedbackAdapter kbFeedbackAdapter,
                                   KnowledgeBuilder knowledgeBuilder,
                                   FeedbackAnalysisService analysisService) {
        this.flowMapper = flowMapper;
        this.kbFeedbackAdapter = kbFeedbackAdapter;
        this.knowledgeBuilder = knowledgeBuilder;
        this.analysisService = analysisService;
    }

    public FeedbackClassifyVO classify(Long flowId, ClassifyRequest request) {
        FeedbackFlow flow = flowMapper.selectById(flowId);
        if (flow == null) {
            throw new BusinessException(FeedbackErrorCode.FLOW_NOT_EXIST, "反馈处理流不存在: flowId=" + flowId);
        }
        if (request == null || request.getChangeType() == null || request.getChangeType().isBlank()) {
            // 入参缺失属参数错误（40000）；45003 仅用于"分析未完成/无 diff 结果"场景
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "change_type 不能为空");
        }
        if (!ChangeType.isValid(request.getChangeType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "change_type 非法");
        }
        // 仅 MANUAL_REQUIRED / 上次 classify 未建审（SUGGESTING）可执行人工判定
        boolean manualRequired = FeedbackAnalyzeStatus.MANUAL_REQUIRED.getValue().equals(flow.getAnalyzeStatus());
        boolean suggesting = FeedbackAnalyzeStatus.SUGGESTING.getValue().equals(flow.getAnalyzeStatus());
        if (!manualRequired && !suggesting) {
            throw new BusinessException(FeedbackErrorCode.STATUS_NOT_ALLOWED,
                    "仅 MANUAL_REQUIRED/SUGGESTING 状态可人工判定，当前 analyze_status=" + flow.getAnalyzeStatus());
        }

        // 1. 记录人工类型与说明（evidence）
        flow.setChangeType(request.getChangeType());
        flow.setChangeConfidence(new BigDecimal("1.000"));
        flow.setAnalyzeStatus(FeedbackAnalyzeStatus.SUGGESTING.getValue());
        String manualNote = request.getComment() == null || request.getComment().isBlank()
                ? "人工判定" : FeedbackSafeText.truncate(request.getComment(), 200);
        flow.setErrorMsg("manual classify: " + manualNote);
        flow.setUpdateTime(new Date());
        flowMapper.updateById(flow);

        // 2. buildSuggestion=false：仅落类型，等待后续人工触发
        FeedbackClassifyVO vo = new FeedbackClassifyVO();
        vo.setFlowId(flow.getId());
        vo.setChangeType(request.getChangeType());
        vo.setAnalyzeStatus(flow.getAnalyzeStatus());

        if (!request.buildSuggestion()) {
            return vo;
        }

        // 3. KnowledgeBuilder 生成建议并逐条建审（复用 analyze 管线后半段）
        try {
            KbFeedbackVO feedback = kbFeedbackAdapter.findByIdentifier(
                    flow.getDsId(), flow.getSessionId(), flow.getFeedbackId());
            String question = feedback == null ? null : feedback.getQuestion();
            String userComment = feedback == null ? null : feedback.getUserComment();
            List<KnowledgeSuggestion> suggestions = knowledgeBuilder.build(
                    flow.getDsId(), question, null, null, userComment,
                    flow.getFeedbackType(), request.getChangeType(),
                    null, null, "manual classify:" + manualNote);

            if (suggestions.isEmpty()) {
                vo.setAnalyzeStatus(flow.getAnalyzeStatus());
                return vo;
            }
            List<Long> taskIds = analysisService.createReviewTasks(flow, suggestions);
            if (!taskIds.isEmpty()) {
                flow.setReviewTaskIds(FeedbackJsonUtil.toJson(taskIds));
                flow.setSuggestions(FeedbackJsonUtil.toJson(suggestions));
                flow.setWikiWritebackFlag(hasWikiTarget(suggestions) ? 1 : 0);
                flow.setAnalyzeStatus(FeedbackAnalyzeStatus.REVIEW_CREATED.getValue());
                flow.setStatus(FeedbackStatus.REVIEWING.getValue());
                flow.setErrorMsg(null);
                flow.setUpdateTime(new Date());
                flowMapper.updateById(flow);
            }
            vo.setAnalyzeStatus(flow.getAnalyzeStatus());
            vo.setReviewTaskIds(taskIds);
            return vo;
        } catch (Exception e) {
            log.warn("feedback: classify 建议建审失败 flowId={}: {}", flowId, e.getMessage());
            flow.setAnalyzeStatus(FeedbackAnalyzeStatus.ANALYZE_FAILED.getValue());
            flow.setStatus(FeedbackStatus.FAILED.getValue());
            flow.setErrorMsg(FeedbackSafeText.truncate(e.getMessage(), 200));
            flow.setUpdateTime(new Date());
            flowMapper.updateById(flow);
            throw new BusinessException(FeedbackErrorCode.SUGGESTION_FAILED,
                    "建议生成失败: " + FeedbackSafeText.truncate(e.getMessage(), 120));
        }
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
}
