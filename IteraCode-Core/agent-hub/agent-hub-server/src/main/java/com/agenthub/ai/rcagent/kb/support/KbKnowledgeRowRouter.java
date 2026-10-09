package com.agenthub.ai.rcagent.kb.support;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.kb.constant.KbErrorCode;
import com.agenthub.ai.rcagent.kb.constant.KbTargetObjectType;
import com.agenthub.ai.rcagent.kb.entity.KbBizLink;
import com.agenthub.ai.rcagent.kb.entity.KbBusinessConcept;
import com.agenthub.ai.rcagent.kb.entity.KbColumn;
import com.agenthub.ai.rcagent.kb.entity.KbKnowledgeRow;
import com.agenthub.ai.rcagent.kb.entity.KbMetric;
import com.agenthub.ai.rcagent.kb.entity.KbRelation;
import com.agenthub.ai.rcagent.kb.entity.KbSqlTemplate;
import com.agenthub.ai.rcagent.kb.entity.KbTable;
import com.agenthub.ai.rcagent.kb.mapper.KbBizLinkMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbBusinessConceptMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbColumnMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbMetricMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbRelationMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbSqlTemplateMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbTableMapper;
import org.springframework.stereotype.Component;

/**
 * 知识对象行路由器：审核批准/拒绝、软删除、置信度更新等横切逻辑按
 * targetObjectType 定位并更新任意一张知识对象表。
 *
 * <p>7 张知识对象表共享 KbKnowledgeRow 公共视图（可信度四元组/状态/计数），
 * 因此路由只做「类型 → 具体 Mapper/实体」的翻译，不承载业务规则。</p>
 */
@Component
public class KbKnowledgeRowRouter {

    private final KbTableMapper tableMapper;
    private final KbColumnMapper columnMapper;
    private final KbBusinessConceptMapper conceptMapper;
    private final KbMetricMapper metricMapper;
    private final KbSqlTemplateMapper sqlTemplateMapper;
    private final KbBizLinkMapper bizLinkMapper;
    private final KbRelationMapper relationMapper;

    public KbKnowledgeRowRouter(KbTableMapper tableMapper,
                                KbColumnMapper columnMapper,
                                KbBusinessConceptMapper conceptMapper,
                                KbMetricMapper metricMapper,
                                KbSqlTemplateMapper sqlTemplateMapper,
                                KbBizLinkMapper bizLinkMapper,
                                KbRelationMapper relationMapper) {
        this.tableMapper = tableMapper;
        this.columnMapper = columnMapper;
        this.conceptMapper = conceptMapper;
        this.metricMapper = metricMapper;
        this.sqlTemplateMapper = sqlTemplateMapper;
        this.bizLinkMapper = bizLinkMapper;
        this.relationMapper = relationMapper;
    }

    /**
     * 按类型+主键取行，不带 ds 隔离校验（详情等只读场景）。
     * 行不存在抛 42001。
     */
    public KbKnowledgeRow selectById(KbTargetObjectType type, Long objectId) {
        return switch (type) {
            case TABLE -> tableMapper.selectById(objectId);
            case COLUMN -> columnMapper.selectById(objectId);
            case BUSINESS_CONCEPT -> conceptMapper.selectById(objectId);
            case METRIC -> metricMapper.selectById(objectId);
            case SQL_TEMPLATE -> sqlTemplateMapper.selectById(objectId);
            case BIZ_LINK -> bizLinkMapper.selectById(objectId);
            case RELATION -> relationMapper.selectById(objectId);
        };
    }

    /**
     * 按类型+主键取行，并校验 ds_id 隔离（BR-004）。
     * 行不存在抛 42001；ds_id 冲突抛 42002。
     */
    public KbKnowledgeRow selectByDsAndId(String dsId, KbTargetObjectType type, Long objectId) {
        KbKnowledgeRow row = selectById(type, objectId);
        if (row == null) {
            throw new BusinessException(KbErrorCode.TARGET_NOT_EXIST, "目标知识不存在: type=" + type.getValue() + ", id=" + objectId);
        }
        if (dsId == null || !dsId.equals(row.getDsId())) {
            throw new BusinessException(KbErrorCode.DS_ID_MISSING_OR_CONFLICT, "ds_id 缺失或隔离冲突: 目标行属于 " + row.getDsId());
        }
        return row;
    }

    /** 按已定位的行更新（行必须携带主键）。 */
    public void updateRow(KbTargetObjectType type, KbKnowledgeRow row) {
        switch (type) {
            case TABLE -> tableMapper.updateById((KbTable) row);
            case COLUMN -> columnMapper.updateById((KbColumn) row);
            case BUSINESS_CONCEPT -> conceptMapper.updateById((KbBusinessConcept) row);
            case METRIC -> metricMapper.updateById((KbMetric) row);
            case SQL_TEMPLATE -> sqlTemplateMapper.updateById((KbSqlTemplate) row);
            case BIZ_LINK -> bizLinkMapper.updateById((KbBizLink) row);
            case RELATION -> relationMapper.updateById((KbRelation) row);
        }
    }
}
