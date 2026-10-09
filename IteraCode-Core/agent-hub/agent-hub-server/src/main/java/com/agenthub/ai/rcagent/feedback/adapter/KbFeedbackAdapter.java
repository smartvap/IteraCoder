package com.agenthub.ai.rcagent.feedback.adapter;

import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackErrorCode;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.rcagent.kb.dto.KbFeedbackPageDTO;
import com.agenthub.ai.rcagent.kb.dto.KbFeedbackSaveDTO;
import com.agenthub.ai.rcagent.kb.service.KbFeedbackService;
import com.agenthub.ai.rcagent.kb.vo.KbFeedbackSaveVO;
import com.agenthub.ai.rcagent.kb.vo.KbFeedbackVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * module-004 kb-storage-review → feedback-loop 交互适配器（brownfield 边界）。
 *
 * <p>feedback 模块与 module-004 kb_feedback 的全部交互集中在本类：反馈落库经
 * {@link KbFeedbackService#save}（进程内 Service 真实签名）；反馈原文回溯经
 * {@link KbFeedbackService#page}（module-004 未提供按 id 读取 Service，MVP 以
 * ds_id + session_id 最近页过滤 feedbackId，新反馈在页首可命中；遗留项见总结）。</p>
 */
@Slf4j
@Component
public class KbFeedbackAdapter {

    private static final int PAGE_SIZE = 200;

    private final KbFeedbackService feedbackService;

    public KbFeedbackAdapter(KbFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    /**
     * 反馈落库（module-004 feedback/save，进程内 Service）。
     * 依赖异常统一收敛为 45005（NFR-BE-003），避免 module-004 内部细节/SQL 片段外溢。
     */
    public KbFeedbackSaveVO save(KbFeedbackSaveDTO dto) {
        try {
            return feedbackService.save(dto);
        } catch (RuntimeException e) {
            log.warn("feedback: module-004 反馈落库失败 dsId={}: {}",
                    dto == null || dto.getDsId() == null ? "" : dto.getDsId(),
                    FeedbackSafeText.truncate(e.getMessage(), 200));
            throw new BusinessException(FeedbackErrorCode.DEPENDENCY_CALL_FAILED,
                    "知识库存储服务不可用，请稍后重试");
        }
    }

    /**
     * 反馈分页查询（module-004 feedback/page）。
     * 依赖异常统一收敛为 45005（NFR-BE-003）。
     */
    public PageResult page(KbFeedbackPageDTO dto) {
        try {
            return feedbackService.page(dto);
        } catch (RuntimeException e) {
            log.warn("feedback: module-004 反馈分页失败 dsId={}: {}",
                    dto == null || dto.getDsId() == null ? "" : dto.getDsId(),
                    FeedbackSafeText.truncate(e.getMessage(), 200));
            throw new BusinessException(FeedbackErrorCode.DEPENDENCY_CALL_FAILED,
                    "知识库查询服务不可用，请稍后重试");
        }
    }

    /**
     * 回溯单条反馈（question/generated_sql/modified_sql）。
     * module-004 未暴露 getById，MVP 通过最近页 + feedbackId 内存匹配。
     *
     * @return 匹配的反馈 VO；未命中返回 null
     */
    public KbFeedbackVO findByIdentifier(String dsId, String sessionId, Long feedbackId) {
        if (dsId == null || feedbackId == null) {
            return null;
        }
        KbFeedbackPageDTO query = new KbFeedbackPageDTO();
        query.setDsId(dsId);
        query.setSessionId(sessionId);
        query.setPage(1);
        query.setPageSize(PAGE_SIZE);
        PageResult result;
        try {
            result = feedbackService.page(query);
        } catch (Exception e) {
            log.warn("feedback: kb_feedback page 回溯失败 dsId={}: {}", dsId, e.getMessage());
            return null;
        }
        if (result == null || result.getRecords() == null) {
            return null;
        }
        List<?> records = result.getRecords();
        for (Object record : records) {
            if (record instanceof KbFeedbackVO vo && feedbackId.equals(vo.getId())) {
                return vo;
            }
        }
        return null;
    }
}
