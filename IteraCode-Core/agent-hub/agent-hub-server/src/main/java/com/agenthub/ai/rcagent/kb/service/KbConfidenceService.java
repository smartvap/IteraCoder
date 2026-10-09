package com.agenthub.ai.rcagent.kb.service;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.rcagent.kb.common.KbJsonUtils;
import com.agenthub.ai.rcagent.kb.constant.KbErrorCode;
import com.agenthub.ai.rcagent.kb.constant.KbKnowledgeStatus;
import com.agenthub.ai.rcagent.kb.constant.KbReviewTaskType;
import com.agenthub.ai.rcagent.kb.constant.KbTargetObjectType;
import com.agenthub.ai.rcagent.kb.dto.KbConfidenceConfirmDTO;
import com.agenthub.ai.rcagent.kb.dto.KbConfidenceSignalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbStructureChangeDTO;
import com.agenthub.ai.rcagent.kb.entity.KbBizLink;
import com.agenthub.ai.rcagent.kb.entity.KbColumn;
import com.agenthub.ai.rcagent.kb.entity.KbKnowledgeRow;
import com.agenthub.ai.rcagent.kb.entity.KbSqlTemplate;
import com.agenthub.ai.rcagent.kb.mapper.KbBizLinkMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbColumnMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbSqlTemplateMapper;
import com.agenthub.ai.rcagent.kb.support.KbKnowledgeRowRouter;
import com.agenthub.ai.rcagent.kb.vo.KbConfidenceConfirmVO;
import com.agenthub.ai.rcagent.kb.vo.KbConfidenceNegativeVO;
import com.agenthub.ai.rcagent.kb.vo.KbConfidencePositiveVO;
import com.agenthub.ai.rcagent.kb.vo.KbStructureChangeVO;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 置信度贝叶斯在线更新（接口16-19，FR-006 公式统一收敛于此，禁止散落 Mapper/Controller）。
 *
 * <ul>
 *   <li>正面：confidence = min(1.0, c + (1-c)*0.05)，hit_count++（慢升）</li>
 *   <li>负面：confidence = c*0.5，error_count++；error≥2 → needs_review；error≥3 → draft（快降）</li>
 *   <li>人工确认：confidence=1.0/error=0/verified=1/status=active</li>
 *   <li>结构变更：引用对象 confidence*0.5 → needs_review</li>
 * </ul>
 *
 * <p>signal_key 幂等使用进程内 ConcurrentHashMap 去重（MVP 单实例内存幂等表），
 * 多实例部署时由调用方保证 signal_key 唯一或扩展持久化幂等表。</p>
 */
@Slf4j
@Service
public class KbConfidenceService {

    private static final BigDecimal ONE = new BigDecimal("1.000");
    private static final BigDecimal HALF = new BigDecimal("0.500");
    private static final BigDecimal STEP = new BigDecimal("0.050");

    /** signal_key 幂等去重集合：key = ds_id|type|object_id|signal_key */
    private final Set<String> signalKeys = ConcurrentHashMap.newKeySet();

    private final KbKnowledgeRowRouter rowRouter;
    private final KbSqlTemplateMapper sqlTemplateMapper;
    private final KbBizLinkMapper bizLinkMapper;
    private final KbColumnMapper columnMapper;
    private final KbReviewService reviewService;

    public KbConfidenceService(KbKnowledgeRowRouter rowRouter,
                               KbSqlTemplateMapper sqlTemplateMapper,
                               KbBizLinkMapper bizLinkMapper,
                               KbColumnMapper columnMapper,
                               KbReviewService reviewService) {
        this.rowRouter = rowRouter;
        this.sqlTemplateMapper = sqlTemplateMapper;
        this.bizLinkMapper = bizLinkMapper;
        this.columnMapper = columnMapper;
        this.reviewService = reviewService;
    }

    /** 接口16 正面信号 */
    public KbConfidencePositiveVO positive(KbConfidenceSignalDTO dto) {
        KbTargetObjectType type = validateCommon(dto);
        String dedupeKey = dedupeKey(dto.getDsId(), type.getValue(), dto.getObjectId(), dto.getSignalKey());
        boolean duplicate = !signalKeys.add(dedupeKey);

        KbKnowledgeRow row = rowRouter.selectByDsAndId(dto.getDsId(), type, dto.getObjectId());
        BigDecimal oldC = row.getConfidence() == null ? new BigDecimal("0.600") : row.getConfidence();

        KbConfidencePositiveVO vo = new KbConfidencePositiveVO();
        vo.setObjectId(row.getId());
        vo.setOldConfidence(oldC);
        vo.setDuplicate(duplicate);
        if (duplicate) {
            vo.setNewConfidence(oldC);
            vo.setHitCount(row.getHitCount());
            return vo;
        }

        // 正 c = min(1.0, c + (1-c)*0.05)
        BigDecimal newC = oldC.add(ONE.subtract(oldC).multiply(STEP)).min(ONE);
        int hit = row.getHitCount() == null ? 0 : row.getHitCount();
        row.setConfidence(newC);
        row.setHitCount(hit + 1);
        row.setEvidence(appendEvidence(row.getEvidence(), "positive signal " + safeKey(dto.getSignalKey())));
        rowRouter.updateRow(type, row);

        vo.setNewConfidence(newC);
        vo.setHitCount(row.getHitCount());
        return vo;
    }

    /** 接口17 负面信号 */
    public KbConfidenceNegativeVO negative(KbConfidenceSignalDTO dto) {
        KbTargetObjectType type = validateCommon(dto);
        String dedupeKey = dedupeKey(dto.getDsId(), type.getValue(), dto.getObjectId(), dto.getSignalKey());
        boolean duplicate = !signalKeys.add(dedupeKey);

        KbKnowledgeRow row = rowRouter.selectByDsAndId(dto.getDsId(), type, dto.getObjectId());
        BigDecimal oldC = row.getConfidence() == null ? new BigDecimal("0.600") : row.getConfidence();

        KbConfidenceNegativeVO vo = new KbConfidenceNegativeVO();
        vo.setObjectId(row.getId());
        vo.setOldConfidence(oldC);
        vo.setDuplicate(duplicate);
        if (duplicate) {
            vo.setNewConfidence(oldC);
            vo.setErrorCount(row.getErrorCount());
            vo.setStatus(row.getStatus());
            return vo;
        }

        // 负 c = c*0.5
        BigDecimal newC = oldC.multiply(HALF);
        int error = (row.getErrorCount() == null ? 0 : row.getErrorCount()) + 1;
        row.setConfidence(newC);
        row.setErrorCount(error);

        String newStatus;
        if (error >= 3) {
            newStatus = KbKnowledgeStatus.DRAFT.getValue();
        } else if (error >= 2) {
            newStatus = KbKnowledgeStatus.NEEDS_REVIEW.getValue();
        } else {
            newStatus = row.getStatus() == null ? KbKnowledgeStatus.DRAFT.getValue() : row.getStatus();
        }
        row.setStatus(newStatus);
        row.setEvidence(appendEvidence(row.getEvidence(),
                "negative signal " + safeKey(dto.getSignalKey()) + " error=" + error));
        rowRouter.updateRow(type, row);

        vo.setNewConfidence(newC);
        vo.setErrorCount(error);
        vo.setStatus(newStatus);

        // error>=2 降级触发自动审核任务（默认开启，MVP 每降级对象一条）
        boolean autoCreate = dto.getAutoCreateTask() == null || Boolean.TRUE.equals(dto.getAutoCreateTask());
        if (autoCreate && error >= 2) {
            vo.setReviewTaskId(autoCreateReview(row, type, "confidence degrade error=" + error));
        }
        return vo;
    }

    /** 接口18 人工确认 */
    public KbConfidenceConfirmVO confirm(KbConfidenceConfirmDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        KbTargetObjectType type = KbTargetObjectType.from(dto.getTargetObjectType());
        ThrowUtils.throwIf(type == null,
                new BusinessException(KbErrorCode.TARGET_TYPE_ILLEGAL, "targetObjectType 非法"));
        ThrowUtils.throwIf(dto.getObjectId() == null,
                new BusinessException(ErrorCode.PARAMS_ERROR, "object_id 不能为空"));
        ThrowUtils.throwIf(dto.getReviewer() == null || dto.getReviewer().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "确认人必填"));

        KbKnowledgeRow row = rowRouter.selectByDsAndId(dto.getDsId(), type, dto.getObjectId());
        row.setConfidence(ONE);
        row.setErrorCount(0);
        row.setVerified(1);
        row.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
        row.setEvidence(appendEvidence(row.getEvidence(),
                "human confirm by " + safeKey(dto.getReviewer())));
        rowRouter.updateRow(type, row);

        KbConfidenceConfirmVO vo = new KbConfidenceConfirmVO();
        vo.setObjectId(row.getId());
        vo.setConfidence(ONE);
        vo.setStatus(KbKnowledgeStatus.ACTIVE.getValue());
        vo.setVerified(1);
        return vo;
    }

    /** 接口19 结构变更影响 */
    public KbStructureChangeVO structureChange(KbStructureChangeDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        ThrowUtils.throwIf(dto.getAffectedTable() == null || dto.getAffectedTable().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "affected_table 不能为空"));

        boolean createReview = dto.getCreateReview() == null || Boolean.TRUE.equals(dto.getCreateReview());
        return degradeByAffectedTable(dto.getDsId(), dto.getAffectedTable(),
                dto.getAffectedColumns(), dto.getChangeType(), dto.getEvidence(), createReview);
    }

    /**
     * 结构变更降级内部实现：反查引用 affected_table 的 SQL 模板与业务链路，
     * 将其 confidence*0.5 并置 needs_review；可顺带对受影响字段列降级。
     * 供接口19与知识保存联动复用。
     *
     * @return 受影响对象统计与自动任务 id
     */
    public KbStructureChangeVO degradeByAffectedTable(String dsId, String affectedTable,
                                                      List<String> affectedColumns, String changeType,
                                                      String evidence, boolean createReview) {
        List<KbSqlTemplate> templates = sqlTemplateMapper.selectImpactedByTable(dsId, affectedTable);
        List<KbBizLink> links = bizLinkMapper.selectImpactedByTable(dsId, affectedTable);

        long tplCount = 0;
        long linkCount = 0;
        long columnCount = 0;
        List<Long> taskIds = new ArrayList<>();
        for (KbSqlTemplate t : templates) {
            t.setConfidence(half(t.getConfidence()));
            t.setStatus(KbKnowledgeStatus.NEEDS_REVIEW.getValue());
            t.setEvidence(appendEvidence(t.getEvidence(), "structure-change table=" + affectedTable
                    + (changeType == null ? "" : " change=" + changeType)));
            sqlTemplateMapper.updateById(t);
            tplCount++;
            if (createReview) {
                taskIds.add(reviewService.createTaskInternal(dsId, KbReviewTaskType.STRUCTURE_IMPACT.getValue(),
                        KbTargetObjectType.SQL_TEMPLATE.getValue(),
                        targetJson(t.getId(), t.getBizAction()),
                        null, "structure-change impact table=" + affectedTable));
            }
        }
        for (KbBizLink l : links) {
            l.setConfidence(half(l.getConfidence()));
            l.setStatus(KbKnowledgeStatus.NEEDS_REVIEW.getValue());
            l.setEvidence(appendEvidence(l.getEvidence(), "structure-change table=" + affectedTable
                    + (changeType == null ? "" : " change=" + changeType)));
            bizLinkMapper.updateById(l);
            linkCount++;
            if (createReview) {
                taskIds.add(reviewService.createTaskInternal(dsId, KbReviewTaskType.STRUCTURE_IMPACT.getValue(),
                        KbTargetObjectType.BIZ_LINK.getValue(),
                        targetJson(l.getId(), l.getBizOperation()),
                        null, "structure-change impact table=" + affectedTable));
            }
        }

        // 受影响字段列降级（仅当显式指定列集合时，对应 COLUMN_ADD/COLUMN_DROP/COLUMN_TYPE）
        if (affectedColumns != null && !affectedColumns.isEmpty()) {
            for (String columnName : affectedColumns) {
                columnCount += degradeColumn(dsId, affectedTable, columnName, changeType);
            }
            // 受影响字段列同样可进入人工复核
            if (createReview) {
                for (String columnName : affectedColumns) {
                    KbColumn col = findColumn(dsId, affectedTable, columnName);
                    if (col != null) {
                        taskIds.add(reviewService.createTaskInternal(dsId, KbReviewTaskType.STRUCTURE_IMPACT.getValue(),
                                KbTargetObjectType.COLUMN.getValue(),
                                targetJson(col.getId(), columnName),
                                null, "structure-change impact table=" + affectedTable + " column=" + columnName));
                    }
                }
            }
        }

        KbStructureChangeVO vo = new KbStructureChangeVO();
        vo.setImpactedTemplates(tplCount);
        vo.setImpactedLinks(linkCount);
        vo.setImpactedColumns(columnCount);
        vo.setReviewTaskIds(taskIds.isEmpty() ? null : taskIds);
        return vo;
    }

    /** 单列降级：将指定列 confidence*0.5 置 needs_review（非 deprecated），返回是否命中 */
    private long degradeColumn(String dsId, String tableName, String columnName, String changeType) {
        KbColumn col = findColumn(dsId, tableName, columnName);
        if (col == null || KbKnowledgeStatus.DEPRECATED.getValue().equals(col.getStatus())) {
            return 0L;
        }
        col.setConfidence(half(col.getConfidence()));
        col.setStatus(KbKnowledgeStatus.NEEDS_REVIEW.getValue());
        col.setEvidence(appendEvidence(col.getEvidence(), "structure-change column=" + columnName
                + (changeType == null ? "" : " change=" + changeType)));
        columnMapper.updateById(col);
        return 1L;
    }

    private KbColumn findColumn(String dsId, String tableName, String columnName) {
        return columnMapper.selectOne(new QueryWrapper<KbColumn>()
                .eq("ds_id", dsId)
                .eq("table_name", tableName)
                .eq("column_name", columnName)
                .last("LIMIT 1"));
    }

    /** 校验公共入参并返回目标类型（接口16/17） */
    private KbTargetObjectType validateCommon(KbConfidenceSignalDTO dto) {
        ThrowUtils.throwIf(dto.getDsId() == null || dto.getDsId().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
        KbTargetObjectType type = KbTargetObjectType.from(dto.getTargetObjectType());
        ThrowUtils.throwIf(type == null,
                new BusinessException(KbErrorCode.TARGET_TYPE_ILLEGAL, "targetObjectType 非法"));
        ThrowUtils.throwIf(dto.getObjectId() == null,
                new BusinessException(ErrorCode.PARAMS_ERROR, "object_id 不能为空"));
        ThrowUtils.throwIf(dto.getSignalKey() == null || dto.getSignalKey().isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "signalKey 不能为空"));
        return type;
    }

    private BigDecimal half(BigDecimal c) {
        if (c == null) {
            return new BigDecimal("0.300");
        }
        return c.multiply(HALF).min(new BigDecimal("0.999"));
    }

    private long autoCreateReview(KbKnowledgeRow row, KbTargetObjectType type, String reason) {
        JSONObject obj = new JSONObject();
        obj.put("objectId", row.getId());
        obj.put("desc", type.getValue() + "#" + row.getId());
        return reviewService.createTaskInternal(row.getDsId(), KbReviewTaskType.FEEDBACK_CORRECTION.getValue(),
                type.getValue(), obj.toJSONString(), null,
                "auto-review " + reason + " signal=" + safeKey(reason));
    }

    private String dedupeKey(String dsId, String type, Long objectId, String signalKey) {
        return dsId + "|" + type + "|" + objectId + "|" + safeKey(signalKey);
    }

    private String targetJson(Long id, String name) {
        JSONObject obj = new JSONObject();
        obj.put("objectId", id);
        obj.put("desc", name == null ? String.valueOf(id) : name);
        return obj.toJSONString();
    }

    private String appendEvidence(String evidence, String snippet) {
        String sep = evidence == null || evidence.isBlank() ? "" : " | ";
        String combined = (evidence == null ? "" : evidence) + sep + snippet;
        int max = 4000;
        return combined.length() > max ? combined.substring(0, max) : combined;
    }

    /** 幂等键/日志片段：signal_key 可能较长，截断保留可追溯特征即可 */
    private String safeKey(String key) {
        if (key == null) {
            return "";
        }
        return key.length() > 100 ? key.substring(0, 100) : key;
    }
}
