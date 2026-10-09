package com.agenthub.ai.rcagent.feedback.service;

import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.rcagent.feedback.adapter.ConfidenceAdapter;
import com.agenthub.ai.rcagent.feedback.adapter.KbFeedbackAdapter;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackAnalyzeStatus;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackStatus;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackType;
import com.agenthub.ai.rcagent.feedback.dto.FeedbackSubmitRequest;
import com.agenthub.ai.rcagent.feedback.dto.KnowledgeRef;
import com.agenthub.ai.rcagent.feedback.entity.FeedbackFlow;
import com.agenthub.ai.rcagent.feedback.mapper.FeedbackFlowMapper;
import com.agenthub.ai.rcagent.feedback.util.FeedbackJsonUtil;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.rcagent.feedback.vo.FeedbackSubmitVO;
import com.agenthub.ai.rcagent.kb.constant.KbFeedbackStatus;
import com.agenthub.ai.rcagent.kb.dto.KbFeedbackSaveDTO;
import com.agenthub.ai.rcagent.kb.vo.KbFeedbackSaveVO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 反馈接收与提交（接口1 POST /api/v1/rca/feedback）。
 *
 * <p>流程：module-004 feedback/save 落 kb_feedback(PENDING) → uk_ds_feedback 幂等
 * （同 (ds_id, feedback_id) 返回已有流 duplicate=true）→ INSERT fb_feedback_flow(RECEIVED)
 * → 异步提交 analyze 管线（HTTP 立即返回，NFR 毫秒级）。POSITIVE/NEGATIVE 且携带
 * relatedKnowledge 引用时同步推进置信度信号（BR-004，signal_key 幂等）。</p>
 */
@Slf4j
@Service
public class FeedbackSubmitService {

    private static final int MAX_QUESTION = 4000;
    private static final int MAX_SQL = 65535;
    private static final int MAX_COMMENT = 2000;
    private static final int MAX_DS_ID = 64;

    private final FeedbackFlowMapper flowMapper;
    private final KbFeedbackAdapter kbFeedbackAdapter;
    private final ConfidenceAdapter confidenceAdapter;
    private final FeedbackAnalysisService analysisService;
    private final ExecutorService analyzeExecutor;

    public FeedbackSubmitService(FeedbackFlowMapper flowMapper,
                                 KbFeedbackAdapter kbFeedbackAdapter,
                                 ConfidenceAdapter confidenceAdapter,
                                 FeedbackAnalysisService analysisService) {
        this.flowMapper = flowMapper;
        this.kbFeedbackAdapter = kbFeedbackAdapter;
        this.confidenceAdapter = confidenceAdapter;
        this.analysisService = analysisService;
        this.analyzeExecutor = Executors.newFixedThreadPool(2);
    }

    /**
     * 提交反馈。
     */
    public FeedbackSubmitVO submit(FeedbackSubmitRequest request) {
        validate(request);

        // 1. module-004 feedback/save 落 kb_feedback（PENDING）
        KbFeedbackSaveVO saved = kbFeedbackAdapter.save(toKbSaveDTO(request));
        ThrowUtils.throwIf(saved == null || saved.getFeedbackId() == null,
                new BusinessException(ErrorCode.OPERATION_ERROR, "反馈落库失败"));

        Long feedbackId = saved.getFeedbackId();

        // 2. uk_ds_feedback 幂等：同 (ds_id, feedback_id) 不新建流
        FeedbackFlow existing = flowMapper.selectOne(new QueryWrapper<FeedbackFlow>()
                .eq("ds_id", request.getDsId())
                .eq("feedback_id", feedbackId)
                .last("LIMIT 1"));
        if (existing != null) {
            FeedbackSubmitVO vo = new FeedbackSubmitVO();
            vo.setFlowId(existing.getId());
            vo.setFeedbackId(feedbackId);
            vo.setStatus(existing.getStatus());
            vo.setDuplicate(true);
            return vo;
        }

        // 3. INSERT fb_feedback_flow(RECEIVED)
        Date now = new Date();
        FeedbackFlow flow = new FeedbackFlow();
        flow.setDsId(request.getDsId());
        flow.setFeedbackId(feedbackId);
        flow.setSessionId(trimTo(request.getSessionId(), 64));
        flow.setFeedbackType(request.getFeedbackType());
        flow.setAnalyzeStatus(FeedbackAnalyzeStatus.RECEIVED.getValue());
        flow.setStatus(FeedbackStatus.RECEIVED.getValue());
        flow.setWikiWritebackFlag(0);
        flow.setConfidencePushed(0);
        flow.setAWritebackDone(0);
        flow.setBWritebackDone(0);
        flow.setRetryCount(0);
        flow.setCreateTime(now);
        flow.setUpdateTime(now);
        flowMapper.insert(flow);

        // 4. POSITIVE/NEGATIVE 且携带引用对象：同步推进置信度信号（signal_key 幂等）
        pushInitialConfidence(flow, request);

        // 5. 异步 analyze（HTTP 先返回；线程内异常已由 analysisService 兜底置 FAILED）
        Long flowId = flow.getId();
        FeedbackSubmitRequest submitRequest = request;
        analyzeExecutor.execute(() -> {
            try {
                analysisService.analyzeWithContext(flowId, submitRequest);
            } catch (Exception e) {
                log.warn("feedback: 异步分析提交异常 flowId={}: {}",
                        flowId, FeedbackSafeText.truncate(e.getMessage(), 200));
            }
        });

        FeedbackSubmitVO vo = new FeedbackSubmitVO();
        vo.setFlowId(flowId);
        vo.setFeedbackId(feedbackId);
        vo.setStatus(flow.getStatus());
        vo.setDuplicate(false);
        return vo;
    }

    /** POSITIVE/NEGATIVE 信号：对引用知识对象推进 confidence（不改变闭环状态） */
    private void pushInitialConfidence(FeedbackFlow flow, FeedbackSubmitRequest request) {
        List<KnowledgeRef> refs = request.getRelatedKnowledge();
        if (refs == null || refs.isEmpty()) {
            return;
        }
        if (!"POSITIVE".equalsIgnoreCase(request.getFeedbackType())
                && !"NEGATIVE".equalsIgnoreCase(request.getFeedbackType())) {
            return;
        }
        List<String> signalKeys = new ArrayList<>();
        for (KnowledgeRef ref : refs) {
            if (ref == null || ref.getTargetObjectType() == null || ref.getObjectId() == null) {
                continue;
            }
            String signalKey = "feedback#" + flow.getId() + ":"
                    + ref.getTargetObjectType() + ":" + ref.getObjectId() + ":"
                    + request.getFeedbackType().toLowerCase();
            try {
                if ("POSITIVE".equalsIgnoreCase(request.getFeedbackType())) {
                    confidenceAdapter.positive(flow.getDsId(), ref.getTargetObjectType(),
                            ref.getObjectId(), signalKey, "feedback#" + flow.getId());
                } else {
                    confidenceAdapter.negative(flow.getDsId(), ref.getTargetObjectType(),
                            ref.getObjectId(), signalKey, "feedback#" + flow.getId());
                }
                signalKeys.add(signalKey);
            } catch (Exception e) {
                log.warn("feedback: 初始置信度信号失败 dsId={} type={}: {}",
                        flow.getDsId(), ref.getTargetObjectType(), e.getMessage());
            }
        }
        if (!signalKeys.isEmpty()) {
            flow.setConfidencePushed(1);
            flow.setSignalKeys(FeedbackJsonUtil.toJson(signalKeys));
            flow.setUpdateTime(new Date());
            flowMapper.updateById(flow);
        }
    }

    private KbFeedbackSaveDTO toKbSaveDTO(FeedbackSubmitRequest request) {
        KbFeedbackSaveDTO dto = new KbFeedbackSaveDTO();
        dto.setDsId(request.getDsId());
        dto.setSessionId(trimTo(request.getSessionId(), 64));
        dto.setQuestion(trimTo(request.getQuestion(), MAX_QUESTION));
        dto.setGeneratedSql(trimTo(request.getGeneratedSql(), MAX_SQL));
        dto.setModifiedSql(trimTo(request.getModifiedSql(), MAX_SQL));
        dto.setFeedbackType(request.getFeedbackType());
        dto.setUserComment(trimTo(request.getUserComment(), MAX_COMMENT));
        dto.setStatus(KbFeedbackStatus.PENDING.getValue());
        return dto;
    }

    private void validate(FeedbackSubmitRequest request) {
        ThrowUtils.throwIf(request == null, new BusinessException(ErrorCode.PARAMS_ERROR, "请求体不能为空"));
        ThrowUtils.throwIf(request.getDsId() == null || request.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(request.getDsId().length() > MAX_DS_ID,
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 过长"));
        ThrowUtils.throwIf(request.getQuestion() == null || request.getQuestion().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "问题不能为空"));
        ThrowUtils.throwIf(request.getQuestion().length() > MAX_QUESTION,
                new BusinessException(ErrorCode.PARAMS_ERROR, "问题过长"));
        ThrowUtils.throwIf(!FeedbackType.isValid(request.getFeedbackType()),
                new BusinessException(ErrorCode.PARAMS_ERROR, "feedback_type 非法"));
        ThrowUtils.throwIf(request.getGeneratedSql() != null && request.getGeneratedSql().length() > MAX_SQL,
                new BusinessException(ErrorCode.PARAMS_ERROR, "SQL 过长"));
        ThrowUtils.throwIf(request.getModifiedSql() != null && request.getModifiedSql().length() > MAX_SQL,
                new BusinessException(ErrorCode.PARAMS_ERROR, "SQL 过长"));
        ThrowUtils.throwIf(request.getUserComment() != null && request.getUserComment().length() > MAX_COMMENT,
                new BusinessException(ErrorCode.PARAMS_ERROR, "反馈文本过长"));
        // CORRECTION 建议携带修正 SQL（为空则视为仅文本纠错，不强制）
        // attributionContext 为弱类型 JSON 快照：服务内仅用于归因类型判定，不落全文日志
        if (request.getAttributionContext() != null
                && request.getAttributionContext().toString().length() > 2000) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "归因上下文过长");
        }
    }

    private String trimTo(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }
}
