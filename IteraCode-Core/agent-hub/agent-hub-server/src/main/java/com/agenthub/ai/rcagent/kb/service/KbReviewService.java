package com.agenthub.ai.rcagent.kb.service;

import com.agenthub.ai.base.common.PageResult;
import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.rcagent.kb.common.KbDateUtils;
import com.agenthub.ai.rcagent.kb.common.KbJsonUtils;
import com.agenthub.ai.rcagent.kb.constant.KbErrorCode;
import com.agenthub.ai.rcagent.kb.constant.KbKnowledgeStatus;
import com.agenthub.ai.rcagent.kb.constant.KbReviewTaskStatus;
import com.agenthub.ai.rcagent.kb.constant.KbReviewTaskType;
import com.agenthub.ai.rcagent.kb.constant.KbTargetObjectType;
import com.agenthub.ai.rcagent.kb.dto.KbReviewApproveDTO;
import com.agenthub.ai.rcagent.kb.dto.KbReviewRejectDTO;
import com.agenthub.ai.rcagent.kb.dto.KbReviewTaskCreateDTO;
import com.agenthub.ai.rcagent.kb.dto.KbReviewTaskPageDTO;
import com.agenthub.ai.rcagent.kb.entity.KbBizLink;
import com.agenthub.ai.rcagent.kb.entity.KbBusinessConcept;
import com.agenthub.ai.rcagent.kb.entity.KbColumn;
import com.agenthub.ai.rcagent.kb.entity.KbKnowledgeRow;
import com.agenthub.ai.rcagent.kb.entity.KbMetric;
import com.agenthub.ai.rcagent.kb.entity.KbRelation;
import com.agenthub.ai.rcagent.kb.entity.KbReviewTask;
import com.agenthub.ai.rcagent.kb.entity.KbSqlTemplate;
import com.agenthub.ai.rcagent.kb.entity.KbTable;
import com.agenthub.ai.rcagent.kb.mapper.KbReviewTaskMapper;
import com.agenthub.ai.rcagent.kb.support.KbKnowledgeRowRouter;
import com.agenthub.ai.rcagent.kb.vo.KbReviewApproveVO;
import com.agenthub.ai.rcagent.kb.vo.KbReviewRejectVO;
import com.agenthub.ai.rcagent.kb.vo.KbReviewTaskCreateVO;
import com.agenthub.ai.rcagent.kb.vo.KbReviewTaskDetailVO;
import com.agenthub.ai.rcagent.kb.vo.KbReviewTaskVO;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * 审核任务中心（接口11-15）。
 *
 * <p>审核批准/拒绝联动目标知识状态（BR-003 必填 reviewer/reviewed_at、拒绝必填意见、
 * 终态幂等 42004）。批准时若请求 applySuggested=true，会先解析 suggested_change 的
 * patch（JSON：operation/patch），按 targetObjectType 白名单把可类型化字段
 * （comment_text/semantic_tags/definition 等）物理应用到目标业务字段后再统一转正
 * （即接口14「修改后通过」闭环）；patch 中无法映射的字段/原文以追加 evidence 方式
 * 保留便于追溯。</p>
 */
@Slf4j
@Service
public class KbReviewService {

    private final KbReviewTaskMapper reviewTaskMapper;
    private final KbKnowledgeRowRouter rowRouter;

    public KbReviewService(KbReviewTaskMapper reviewTaskMapper,
                           KbKnowledgeRowRouter rowRouter) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.rowRouter = rowRouter;
    }

    /**
     * 内部创建任务（供保存 LLM 知识/置信度降级/结构变更联动复用），不经过 HTTP DTO。
     */
    public Long createTaskInternal(String dsId, String taskType, String targetObjectType,
                                   String targetObject, String suggestedChange, String evidence) {
        KbReviewTask task = new KbReviewTask();
        task.setDsId(dsId);
        task.setTaskType(taskType);
        task.setTargetObjectType(targetObjectType);
        task.setTargetObject(targetObject);
        task.setSuggestedChange(suggestedChange);
        task.setEvidence(evidence);
        task.setStatus(KbReviewTaskStatus.PENDING.getValue());
        task.setCreateTime(new Date());
        task.setUpdateTime(new Date());
        reviewTaskMapper.insert(task);
        return task.getId();
    }

    /** 接口11 创建审核任务 */
    public KbReviewTaskCreateVO create(KbReviewTaskCreateDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(!KbReviewTaskType.isValid(dto.getTaskType()),
                new BusinessException(ErrorCode.PARAMS_ERROR, "taskType 非法"));
        KbTargetObjectType type = KbTargetObjectType.from(dto.getTargetObjectType());
        ThrowUtils.throwIf(type == null,
                new BusinessException(KbErrorCode.TARGET_TYPE_ILLEGAL, "targetObjectType 非法"));
        ThrowUtils.throwIf(dto.getTargetObject() == null || dto.getTargetObject().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "target_object 不能为空"));

        Long taskId = createTaskInternal(dto.getDsId(), dto.getTaskType(), type.getValue(),
                dto.getTargetObject(), dto.getSuggestedChange(), dto.getEvidence());
        KbReviewTaskCreateVO vo = new KbReviewTaskCreateVO();
        vo.setTaskId(taskId);
        vo.setStatus(KbReviewTaskStatus.PENDING.getValue());
        return vo;
    }

    /** 接口12 审核任务分页 */
    public PageResult page(KbReviewTaskPageDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));

        // 分页由 MP PaginationInnerInterceptor 提供：显式传入 IPage 首参触发，SQL 本身不写 limit
        IPage<KbReviewTask> page = reviewTaskMapper.selectPageTasks(
                new Page<>(dto.page(), dto.pageSize()),
                dto.getDsId(),
                dto.getStatus(),
                dto.getTaskType(),
                dto.getTargetObjectType(),
                dto.getReviewer(),
                KbDateUtils.parse(dto.getStartTime()),
                KbDateUtils.parse(dto.getEndTime()));
        List<KbReviewTaskVO> records = new ArrayList<>(page.getRecords().size());
        for (KbReviewTask task : page.getRecords()) {
            records.add(toVO(task));
        }
        return new PageResult(page.getTotal(), records);
    }

    /** 接口13 审核任务详情（含目标对象当前详情） */
    public KbReviewTaskDetailVO detail(Long id) {
        ThrowUtils.throwIf(id == null || id <= 0,
                new BusinessException(ErrorCode.PARAMS_ERROR, "id 非法"));
        KbReviewTask task = reviewTaskMapper.selectById(id);
        ThrowUtils.throwIf(task == null,
                new BusinessException(ErrorCode.NOT_FOUND_ERROR, "审核任务不存在: id=" + id));
        KbReviewTaskDetailVO vo = new KbReviewTaskDetailVO();
        vo.setTask(toVO(task));
        vo.setTargetDetail(loadTargetDetail(task));
        return vo;
    }

    /**
     * 解析 target_object JSON（{objectId, desc}），尝试定位目标知识当前详情。
     * 目标行可能已删除/不存在，返回 null。
     */
    private Object loadTargetDetail(KbReviewTask task) {
        KbTargetObjectType type = KbTargetObjectType.from(task.getTargetObjectType());
        if (type == null) {
            return null;
        }
        try {
            JSONObject obj = KbJsonUtils.parseObject(task.getTargetObject());
            if (obj == null || obj.getLong("objectId") == null) {
                return null;
            }
            KbKnowledgeRow row = rowRouter.selectById(type, obj.getLong("objectId"));
            return row == null ? null : row;
        } catch (Exception e) {
            log.warn("审核任务 {} 目标详情解析失败, type={}", task.getId(), type.getValue());
            return null;
        }
    }

    /** 接口14 审核批准 */
    public KbReviewApproveVO approve(Long id, KbReviewApproveDTO dto) {
        ThrowUtils.throwIf(dto.getReviewer() == null || dto.getReviewer().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "审核人必填（BR-003 不可匿名）"));
        ThrowUtils.throwIf(dto.getReviewComment() == null || dto.getReviewComment().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "审核意见必填"));

        KbReviewTask task = reviewTaskMapper.selectById(id);
        ThrowUtils.throwIf(task == null,
                new BusinessException(ErrorCode.NOT_FOUND_ERROR, "审核任务不存在: id=" + id));
        checkPending(task);

        boolean targetUpdated = false;
        KbTargetObjectType type = KbTargetObjectType.from(task.getTargetObjectType());
        if (type != null && task.getTargetObject() != null) {
            try {
                JSONObject obj = KbJsonUtils.parseObject(task.getTargetObject());
                if (obj != null && obj.getLong("objectId") != null) {
                    KbKnowledgeRow row = rowRouter.selectByDsAndId(task.getDsId(), type, obj.getLong("objectId"));
                    if (row != null) {
                        // applySuggested 语义（修改后通过）：解析 suggested_change.patch 并类型化应用业务字段，
                        // 无法映射的字段/原文以 evidence 保留追溯；之后再统一置 active 转正
                        if (Boolean.TRUE.equals(dto.getApplySuggested()) && task.getSuggestedChange() != null) {
                            applySuggestedPatch(row, type, task.getSuggestedChange(), task.getId());
                            row.setEvidence(appendEvidence(row.getEvidence(),
                                    "applySuggested review#" + task.getId() + ":" + task.getSuggestedChange()));
                        }
                        // 批准联动：目标 active/confidence=1.0/error=0/verified=1（draft 也直接转正）
                        row.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
                        row.setVerified(1);
                        row.setErrorCount(0);
                        row.setConfidence(new java.math.BigDecimal("1.000"));
                        row.setEvidence(appendEvidence(row.getEvidence(),
                                "approved review#" + task.getId() + " by " + dto.getReviewer()));
                        rowRouter.updateRow(type, row);
                        targetUpdated = true;
                    }
                }
            } catch (BusinessException be) {
                // 目标行不存在/隔离冲突不影响任务自身批准：仅 targetUpdated=false
                if (be.getCode() == KbErrorCode.TARGET_NOT_EXIST) {
                    log.warn("审核任务 {} 目标行不存在，仅批准任务", task.getId());
                } else {
                    throw be;
                }
            }
        }

        task.setStatus(KbReviewTaskStatus.APPROVED.getValue());
        task.setReviewer(dto.getReviewer());
        task.setReviewComment(dto.getReviewComment());
        task.setReviewedAt(new Date());
        task.setUpdateTime(new Date());
        reviewTaskMapper.updateById(task);

        KbReviewApproveVO vo = new KbReviewApproveVO();
        vo.setTaskId(task.getId());
        vo.setStatus(KbReviewTaskStatus.APPROVED.getValue());
        vo.setTargetUpdated(targetUpdated);
        return vo;
    }

    /** 接口15 审核拒绝 */
    public KbReviewRejectVO reject(Long id, KbReviewRejectDTO dto) {
        ThrowUtils.throwIf(dto.getReviewer() == null || dto.getReviewer().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "审核人必填（BR-003 不可匿名）"));
        ThrowUtils.throwIf(dto.getReviewComment() == null || dto.getReviewComment().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "拒绝必须附意见"));

        KbReviewTask task = reviewTaskMapper.selectById(id);
        ThrowUtils.throwIf(task == null,
                new BusinessException(ErrorCode.NOT_FOUND_ERROR, "审核任务不存在: id=" + id));
        checkPending(task);

        if (Boolean.TRUE.equals(dto.getDeprecateTarget())) {
            deprecateTarget(task);
        }

        task.setStatus(KbReviewTaskStatus.REJECTED.getValue());
        task.setReviewer(dto.getReviewer());
        task.setReviewComment(dto.getReviewComment());
        task.setReviewedAt(new Date());
        task.setUpdateTime(new Date());
        reviewTaskMapper.updateById(task);

        KbReviewRejectVO vo = new KbReviewRejectVO();
        vo.setTaskId(task.getId());
        vo.setStatus(KbReviewTaskStatus.REJECTED.getValue());
        if (Boolean.TRUE.equals(dto.getReopenSuggested())) {
            Long newTaskId = createTaskInternal(task.getDsId(), task.getTaskType(), task.getTargetObjectType(),
                    task.getTargetObject(), task.getSuggestedChange(),
                    "reopen from review#" + task.getId() + ":" + safeSnippet(task.getEvidence()));
            vo.setNewTaskId(newTaskId);
        }
        return vo;
    }

    /** 拒绝时按请求将目标知识置 deprecated */
    private void deprecateTarget(KbReviewTask task) {
        KbTargetObjectType type = KbTargetObjectType.from(task.getTargetObjectType());
        if (type == null || task.getTargetObject() == null) {
            return;
        }
        try {
            JSONObject obj = KbJsonUtils.parseObject(task.getTargetObject());
            if (obj == null || obj.getLong("objectId") == null) {
                return;
            }
            KbKnowledgeRow row = rowRouter.selectByDsAndId(task.getDsId(), type, obj.getLong("objectId"));
            if (row == null || KbKnowledgeStatus.DEPRECATED.getValue().equals(row.getStatus())) {
                return;
            }
            row.setStatus(KbKnowledgeStatus.DEPRECATED.getValue());
            row.setEvidence(appendEvidence(row.getEvidence(),
                    "rejected review#" + task.getId() + " deprecate by " + task.getReviewer()));
            rowRouter.updateRow(type, row);
        } catch (BusinessException be) {
            log.warn("审核任务 {} 目标 deprecated 失败: code={}", task.getId(), be.getCode());
        }
    }

    /** 仅 pending 可审批；终态重复返回 42004 */
    private void checkPending(KbReviewTask task) {
        if (!KbReviewTaskStatus.PENDING.getValue().equals(task.getStatus())) {
            throw new BusinessException(KbErrorCode.REVIEW_IDEMPOTENT_CONFLICT,
                    "审核任务已 " + task.getStatus() + "，不允许重复审批（幂等冲突）");
        }
    }

    /**
     * applySuggested 语义落地：解析 suggested_change（JSON：{operation, patch}），
     * 按 targetObjectType 将 patch 中可类型化的白名单业务字段应用到目标行实体，
     * 实现接口14「修改后通过」闭环。patch 中的字段名与保存接口入参一致（snake_case），
     * 例如 comment_text/semantic_tags/data_type/is_pk/is_fk/definition 等。
     * 无法白名单映射/解析失败的字段不应用（由调用方追加 evidence 保留原文追溯），
     * 解析异常仅告警不阻断批准。
     *
     * @param row            目标行（已被 Router 定位，可按类型强转）
     * @param type           目标对象类型
     * @param suggestedChange 审核任务建议 JSON 原文
     * @param taskId         审核任务 id（仅用于告警日志，不落原文）
     */
    private void applySuggestedPatch(KbKnowledgeRow row, KbTargetObjectType type,
                                     String suggestedChange, Long taskId) {
        if (suggestedChange == null || suggestedChange.isBlank()) {
            return;
        }
        try {
            JSONObject change = JSON.parseObject(suggestedChange);
            if (change == null) {
                return;
            }
            JSONObject patch = change.getJSONObject("patch");
            if (patch == null || patch.isEmpty()) {
                return;
            }
            switch (type) {
                case TABLE -> applyTablePatch((KbTable) row, patch);
                case COLUMN -> applyColumnPatch((KbColumn) row, patch);
                case BUSINESS_CONCEPT -> applyConceptPatch((KbBusinessConcept) row, patch);
                case METRIC -> applyMetricPatch((KbMetric) row, patch);
                case SQL_TEMPLATE -> applyTemplatePatch((KbSqlTemplate) row, patch);
                case BIZ_LINK -> applyBizLinkPatch((KbBizLink) row, patch);
                case RELATION -> applyRelationPatch((KbRelation) row, patch);
                default -> log.warn("审核任务 {} applySuggested 类型不支持 type={}", taskId, type.getValue());
            }
        } catch (Exception e) {
            log.warn("审核任务 {} applySuggested patch 应用失败 type={}", taskId, type.getValue());
        }
    }

    /** patch 文本字段应用（存在且非空才覆盖） */
    private void applyText(JSONObject patch, String key, Consumer<String> setter) {
        String value = patch.getString(key);
        if (value != null) {
            setter.accept(value);
        }
    }

    /** patch JSON 数组字段应用为 JSON 文本（如 semantic_tags/synonyms） */
    private void applyStringArray(JSONObject patch, String key, Consumer<String> setter) {
        JSONArray array = patch.getJSONArray(key);
        if (array != null) {
            setter.accept(KbJsonUtils.toJson(array.toJavaList(String.class)));
        }
    }

    /** patch 整型字段应用（0/1 等） */
    private void applyInteger(JSONObject patch, String key, Consumer<Integer> setter) {
        Integer value = patch.getInteger(key);
        if (value != null) {
            setter.accept(value);
        }
    }

    private void applyTablePatch(KbTable row, JSONObject patch) {
        applyText(patch, "comment_text", row::setCommentText);
        applyText(patch, "table_type", row::setTableType);
        applyStringArray(patch, "semantic_tags", row::setSemanticTags);
    }

    private void applyColumnPatch(KbColumn row, JSONObject patch) {
        applyText(patch, "comment_text", row::setCommentText);
        applyText(patch, "data_type", row::setDataType);
        applyStringArray(patch, "semantic_tags", row::setSemanticTags);
        applyInteger(patch, "is_pk", row::setIsPk);
        applyInteger(patch, "is_fk", row::setIsFk);
    }

    private void applyConceptPatch(KbBusinessConcept row, JSONObject patch) {
        applyText(patch, "definition", row::setDefinition);
        applyStringArray(patch, "synonyms", row::setSynonyms);
        applyStringArray(patch, "related_tables", row::setRelatedTables);
        applyStringArray(patch, "related_columns", row::setRelatedColumns);
    }

    private void applyMetricPatch(KbMetric row, JSONObject patch) {
        applyText(patch, "definition", row::setDefinition);
        applyText(patch, "calculation_rule", row::setCalculationRule);
        applyStringArray(patch, "related_tables", row::setRelatedTables);
        applyStringArray(patch, "related_columns", row::setRelatedColumns);
    }

    private void applyTemplatePatch(KbSqlTemplate row, JSONObject patch) {
        applyText(patch, "biz_action", row::setBizAction);
        applyStringArray(patch, "sample_questions", row::setSampleQuestions);
        applyStringArray(patch, "involved_tables", row::setInvolvedTables);
    }

    private void applyBizLinkPatch(KbBizLink row, JSONObject patch) {
        applyText(patch, "biz_operation", row::setBizOperation);
        applyText(patch, "service_class", row::setServiceClass);
        applyText(patch, "dao_class", row::setDaoClass);
        applyStringArray(patch, "related_tables", row::setRelatedTables);
    }

    private void applyRelationPatch(KbRelation row, JSONObject patch) {
        // 关系定位键（table_a/table_b/relation_type）不允许 patch 篡改，仅支持语义字段
        applyText(patch, "column_a", row::setColumnA);
        applyText(patch, "column_b", row::setColumnB);
        JSONArray joinFields = patch.getJSONArray("join_fields");
        if (joinFields != null) {
            // join_fields 为 JSON 二维数组（如 [["a.cust_id","b.id"]]），保持原 JSON 文本写入
            row.setJoinFields(JSON.toJSONString(joinFields));
        }
    }

    private KbReviewTaskVO toVO(KbReviewTask task) {
        KbReviewTaskVO vo = new KbReviewTaskVO();
        BeanUtils.copyProperties(task, vo);
        return vo;
    }

    /** evidence 追加片段（避免无限膨胀截断到合理长度；不记录敏感字面量） */
    private String appendEvidence(String evidence, String snippet) {
        String sep = evidence == null || evidence.isBlank() ? "" : " | ";
        String combined = (evidence == null ? "" : evidence) + sep + snippet;
        int max = 4000;
        return combined.length() > max ? combined.substring(0, max) : combined;
    }

    /** evidence 追加片段日志安全截断（不含敏感完整原文） */
    private String safeSnippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
