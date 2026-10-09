package com.agenthub.ai.rcagent.kb.service;

import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.rcagent.kb.common.KbDateUtils;
import com.agenthub.ai.rcagent.kb.constant.ChangeType;
import com.agenthub.ai.rcagent.kb.constant.FeedbackType;
import com.agenthub.ai.rcagent.kb.constant.KbFeedbackStatus;
import com.agenthub.ai.rcagent.kb.dto.KbFeedbackPageDTO;
import com.agenthub.ai.rcagent.kb.dto.KbFeedbackSaveDTO;
import com.agenthub.ai.rcagent.kb.entity.KbFeedback;
import com.agenthub.ai.rcagent.kb.mapper.KbFeedbackMapper;
import com.agenthub.ai.rcagent.kb.vo.KbFeedbackSaveVO;
import com.agenthub.ai.rcagent.kb.vo.KbFeedbackVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 反馈记录存储与查询（接口25-26）。
 *
 * <p>本模块只存储反馈记录（question/generated_sql/modified_sql 属 P2 场景化内容，
 * 日志不落全文），修改类型判断/建议生成归 feedback-loop（FR-BE-008 范围外）。</p>
 */
@Service
public class KbFeedbackService {

    private final KbFeedbackMapper feedbackMapper;

    public KbFeedbackService(KbFeedbackMapper feedbackMapper) {
        this.feedbackMapper = feedbackMapper;
    }

    /** 接口25 反馈落库 */
    public KbFeedbackSaveVO save(KbFeedbackSaveDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(dto.getQuestion() == null || dto.getQuestion().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "question 不能为空"));
        if (dto.getChangeType() != null && !dto.getChangeType().isBlank()) {
            ThrowUtils.throwIf(!ChangeType.isValid(dto.getChangeType()),
                    new BusinessException(ErrorCode.PARAMS_ERROR, "change_type 非法"));
        }
        if (dto.getFeedbackType() != null && !dto.getFeedbackType().isBlank()) {
            ThrowUtils.throwIf(!FeedbackType.isValid(dto.getFeedbackType()),
                    new BusinessException(ErrorCode.PARAMS_ERROR, "feedback_type 非法"));
        }
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            ThrowUtils.throwIf(!KbFeedbackStatus.isValid(dto.getStatus()),
                    new BusinessException(ErrorCode.PARAMS_ERROR, "status 非法"));
        }

        Date now = new Date();
        KbFeedback entity = new KbFeedback();
        BeanUtils.copyProperties(dto, entity);
        entity.setFeedbackType(dto.getFeedbackType() == null ? FeedbackType.POSITIVE.getValue() : dto.getFeedbackType());
        entity.setStatus(dto.getStatus() == null ? KbFeedbackStatus.PENDING.getValue() : dto.getStatus());
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        feedbackMapper.insert(entity);

        KbFeedbackSaveVO vo = new KbFeedbackSaveVO();
        vo.setFeedbackId(entity.getId());
        vo.setStatus(entity.getStatus());
        return vo;
    }

    /** 接口26 反馈分页查询 */
    public PageResult page(KbFeedbackPageDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));

        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        IPage<KbFeedback> page = feedbackMapper.selectPageFeedbacks(
                new Page<>(dto.page(), dto.pageSize()),
                dto.getDsId(), dto.getStatus(), dto.getSessionId(),
                KbDateUtils.parse(dto.getStartTime()), KbDateUtils.parse(dto.getEndTime()));

        List<KbFeedbackVO> records = new ArrayList<>();
        for (KbFeedback f : page.getRecords()) {
            KbFeedbackVO vo = new KbFeedbackVO();
            BeanUtils.copyProperties(f, vo);
            records.add(vo);
        }
        return new PageResult(page.getTotal(), records);
    }
}
