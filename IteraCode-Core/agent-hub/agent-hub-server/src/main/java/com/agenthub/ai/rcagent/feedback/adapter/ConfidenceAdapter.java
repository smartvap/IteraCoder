package com.agenthub.ai.rcagent.feedback.adapter;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackErrorCode;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.rcagent.kb.dto.KbConfidenceConfirmDTO;
import com.agenthub.ai.rcagent.kb.dto.KbConfidenceSignalDTO;
import com.agenthub.ai.rcagent.kb.service.KbConfidenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * module-004 kb-confidence（置信度引擎）→ feedback-loop 交互适配器（brownfield 边界）。
 *
 * <p>BR-004 公式（正 min(1.0,c+(1-c)*0.05)/hit++、负 c*0.5/error++、批准→1.0/error=0/active）
 * 收敛在 module-004 {@link KbConfidenceService}，本模块仅组装 signal_key 幂等参数转发，
 * 并在 flow.signal_keys 中记录已用幂等键。</p>
 */
@Slf4j
@Component
public class ConfidenceAdapter {

    private final KbConfidenceService confidenceService;

    public ConfidenceAdapter(KbConfidenceService confidenceService) {
        this.confidenceService = confidenceService;
    }

    /** 人工确认（confidence/confirm）：approved 目标 → 1.0/error=0/active */
    public void confirm(String dsId, String targetObjectType, Long objectId,
                        String reviewer, String evidence) {
        if (targetObjectType == null || objectId == null) {
            return;
        }
        try {
            KbConfidenceConfirmDTO dto = new KbConfidenceConfirmDTO();
            dto.setDsId(dsId);
            dto.setTargetObjectType(targetObjectType);
            dto.setObjectId(objectId);
            dto.setReviewer(reviewer);
            dto.setEvidence(evidence);
            confidenceService.confirm(dto);
        } catch (RuntimeException e) {
            // module-004 置信度服务依赖异常统一收敛 45005（NFR-BE-003）
            log.warn("feedback: module-004 置信度确认失败 dsId={} type={}: {}",
                    dsId == null ? "" : dsId, targetObjectType,
                    FeedbackSafeText.truncate(e.getMessage(), 200));
            throw new BusinessException(FeedbackErrorCode.DEPENDENCY_CALL_FAILED,
                    "置信度服务不可用，请稍后重试");
        }
    }

    /** 正面信号（confidence/positive，signal_key 幂等） */
    public void positive(String dsId, String targetObjectType, Long objectId,
                         String signalKey, String evidence) {
        if (targetObjectType == null || objectId == null) {
            return;
        }
        try {
            KbConfidenceSignalDTO dto = new KbConfidenceSignalDTO();
            dto.setDsId(dsId);
            dto.setTargetObjectType(targetObjectType);
            dto.setObjectId(objectId);
            dto.setSignalKey(signalKey);
            dto.setEvidence(evidence);
            confidenceService.positive(dto);
        } catch (RuntimeException e) {
            log.warn("feedback: module-004 置信度正面信号失败 dsId={} type={}: {}",
                    dsId == null ? "" : dsId, targetObjectType,
                    FeedbackSafeText.truncate(e.getMessage(), 200));
            throw new BusinessException(FeedbackErrorCode.DEPENDENCY_CALL_FAILED,
                    "置信度服务不可用，请稍后重试");
        }
    }

    /** 负面信号（confidence/negative，signal_key 幂等） */
    public void negative(String dsId, String targetObjectType, Long objectId,
                         String signalKey, String evidence) {
        if (targetObjectType == null || objectId == null) {
            return;
        }
        try {
            KbConfidenceSignalDTO dto = new KbConfidenceSignalDTO();
            dto.setDsId(dsId);
            dto.setTargetObjectType(targetObjectType);
            dto.setObjectId(objectId);
            dto.setSignalKey(signalKey);
            dto.setEvidence(evidence);
            confidenceService.negative(dto);
        } catch (RuntimeException e) {
            log.warn("feedback: module-004 置信度负面信号失败 dsId={} type={}: {}",
                    dsId == null ? "" : dsId, targetObjectType,
                    FeedbackSafeText.truncate(e.getMessage(), 200));
            throw new BusinessException(FeedbackErrorCode.DEPENDENCY_CALL_FAILED,
                    "置信度服务不可用，请稍后重试");
        }
    }
}
