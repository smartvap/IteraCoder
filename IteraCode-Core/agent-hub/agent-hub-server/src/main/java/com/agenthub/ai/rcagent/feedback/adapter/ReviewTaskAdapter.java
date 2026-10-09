package com.agenthub.ai.rcagent.feedback.adapter;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.feedback.constant.FeedbackErrorCode;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.rcagent.kb.constant.KbReviewTaskType;
import com.agenthub.ai.rcagent.kb.constant.KbTargetObjectType;
import com.agenthub.ai.rcagent.kb.dto.KbReviewTaskCreateDTO;
import com.agenthub.ai.rcagent.kb.service.KbReviewService;
import com.agenthub.ai.rcagent.kb.vo.KbReviewTaskCreateVO;
import com.agenthub.ai.rcagent.kb.vo.KbReviewTaskDetailVO;
import com.agenthub.ai.rcagent.kb.vo.KbReviewTaskVO;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * module-004 kb-review（审核任务中心）→ feedback-loop 交互适配器（brownfield 边界）。
 *
 * <p>feedback 模块创建审核任务 / 轮询审核状态经 {@link KbReviewService} 进程内调用：
 * <ul>
 *   <li>创建：review/task/create（task_type 覆盖 FEEDBACK_CORRECTION/METRIC_DEFINITION/
 *       CONCEPT_DEFINITION/RELATION_SUGGESTION/TEMPLATE_PROMOTE，evidence 带 feedback#flowId）；</li>
 *   <li>轮询：按 task id 取详情判断 approved（scheduler 以 fb_feedback_flow.review_task_ids
 *       驱动，逐任务 detail 判断终态）。</li>
 * </ul>
 * 批准/拒绝动作由 module-004 review approve/reject 完成，本模块不重复暴露。</p>
 */
@Slf4j
@Component
public class ReviewTaskAdapter {

    private final KbReviewService reviewService;

    public ReviewTaskAdapter(KbReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 创建审核任务（review/task/create 进程内 Service）。
     *
     * @param dsId              数据源
     * @param taskType          任务类型（FEEDBACK_CORRECTION/METRIC_DEFINITION/...）
     * @param targetObjectType  目标对象类型（KbTargetObjectType 值）
     * @param targetObjectJson  目标对象标识 JSON {objectId, desc}
     * @param suggestedChange  结构化建议 JSON {operation, patch}（可空）
     * @param evidence          证据（feedback#flowId + diff 引用）
     * @return kb_review_task.id
     */
    public Long createTask(String dsId, String taskType, String targetObjectType,
                           String targetObjectJson, String suggestedChange, String evidence) {
        KbReviewTaskCreateDTO dto = new KbReviewTaskCreateDTO();
        dto.setDsId(dsId);
        dto.setTaskType(taskType);
        dto.setTargetObjectType(targetObjectType);
        dto.setTargetObject(targetObjectJson);
        dto.setSuggestedChange(suggestedChange);
        dto.setEvidence(evidence);
        try {
            KbReviewTaskCreateVO vo = reviewService.create(dto);
            return vo == null ? null : vo.getTaskId();
        } catch (RuntimeException e) {
            // module-004 审核任务创建失败统一收敛 45005（NFR-BE-003），日志不落建议原文
            log.warn("feedback: module-004 审核任务创建失败 dsId={}: {}",
                    dsId == null ? "" : dsId, FeedbackSafeText.truncate(e.getMessage(), 200));
            throw new BusinessException(FeedbackErrorCode.DEPENDENCY_CALL_FAILED,
                    "审核任务创建服务不可用，请稍后重试");
        }
    }

    /**
     * 审核任务详情（scheduler 轮询终态）。
     * 依赖异常统一收敛为 45005；listByIds 内部已逐条兜底，不影响轮询整体。
     */
    public KbReviewTaskDetailVO detail(Long taskId) {
        if (taskId == null || taskId <= 0) {
            return null;
        }
        try {
            return reviewService.detail(taskId);
        } catch (RuntimeException e) {
            log.warn("feedback: module-004 审核任务详情读取失败 taskId={}: {}",
                    taskId, FeedbackSafeText.truncate(e.getMessage(), 200));
            throw new BusinessException(FeedbackErrorCode.DEPENDENCY_CALL_FAILED,
                    "审核任务查询服务不可用，请稍后重试");
        }
    }

    /**
     * 批量读取任务详情并返回任务 VO（存在性过滤）。
     */
    public List<KbReviewTaskVO> listByIds(List<Long> taskIds) {
        List<KbReviewTaskVO> result = new ArrayList<>();
        if (taskIds == null) {
            return result;
        }
        for (Long id : taskIds) {
            try {
                KbReviewTaskDetailVO detail = detail(id);
                if (detail != null && detail.getTask() != null) {
                    result.add(detail.getTask());
                }
            } catch (Exception e) {
                // 单任务读取失败不阻断整体轮询（幂等重试，日志截断不落原文）
                log.warn("feedback: 审核任务详情读取失败 taskId={}: {}",
                        id, FeedbackSafeText.truncate(e.getMessage(), 200));
            }
        }
        return result;
    }

    /** 审核任务状态是否 approved */
    public static boolean isApproved(KbReviewTaskVO task) {
        return task != null && "approved".equalsIgnoreCase(task.getStatus());
    }

    /** 审核任务状态是否 rejected */
    public static boolean isRejected(KbReviewTaskVO task) {
        return task != null && "rejected".equalsIgnoreCase(task.getStatus());
    }

    /** 任务类型合法性（供 KnowledgeBuilder 建议生成前校验，避免 004 抛非法） */
    public static boolean isValidTaskType(String type) {
        return type != null && KbReviewTaskType.isValid(type);
    }

    /** 目标对象类型合法性 */
    public static boolean isValidTargetObjectType(String type) {
        return type != null && KbTargetObjectType.isValid(type);
    }

    /** 目标对象 JSON 构造 {objectId, desc} */
    public static String targetObjectJson(Long objectId, String desc) {
        return JSON.toJSONString(new TargetObject(objectId, desc));
    }

    private record TargetObject(Long objectId, String desc) {
    }
}
