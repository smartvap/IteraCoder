package com.agenthub.ai.rcagent.kb.service;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.base.common.ErrorCode;
import com.agenthub.ai.base.exception.ThrowUtils;
import com.agenthub.ai.rcagent.kb.dto.KbBizLinkRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbRelationRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSchemaRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSemanticRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbTemplateRetrievalDTO;
import com.agenthub.ai.rcagent.kb.entity.KbBizLink;
import com.agenthub.ai.rcagent.kb.entity.KbBusinessConcept;
import com.agenthub.ai.rcagent.kb.entity.KbColumn;
import com.agenthub.ai.rcagent.kb.entity.KbIndex;
import com.agenthub.ai.rcagent.kb.entity.KbMetric;
import com.agenthub.ai.rcagent.kb.entity.KbRelation;
import com.agenthub.ai.rcagent.kb.entity.KbSqlTemplate;
import com.agenthub.ai.rcagent.kb.entity.KbTable;
import com.agenthub.ai.rcagent.kb.mapper.KbBizLinkMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbBusinessConceptMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbColumnMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbIndexMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbMetricMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbRelationMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbSqlTemplateMapper;
import com.agenthub.ai.rcagent.kb.mapper.KbTableMapper;
import com.agenthub.ai.rcagent.kb.vo.KbBizLinkRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbBizLinkVO;
import com.agenthub.ai.rcagent.kb.vo.KbColumnVO;
import com.agenthub.ai.rcagent.kb.vo.KbIndexVO;
import com.agenthub.ai.rcagent.kb.vo.KbConceptVO;
import com.agenthub.ai.rcagent.kb.vo.KbMetricVO;
import com.agenthub.ai.rcagent.kb.vo.KbRelationRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbRelationVO;
import com.agenthub.ai.rcagent.kb.vo.KbSchemaRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbSemanticRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbTableVO;
import com.agenthub.ai.rcagent.kb.vo.KbTemplateRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbTemplateVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 知识检索只读 API（接口20-24）：五路召回，强制 ds_id 过滤，
 * 默认仅返回 verified=1 AND status=active 高置信知识并按 confidence 排序。
 */
@Slf4j
@Service
public class KbRetrievalService {

    private final KbTableMapper tableMapper;
    private final KbColumnMapper columnMapper;
    private final KbIndexMapper indexMapper;
    private final KbSqlTemplateMapper sqlTemplateMapper;
    private final KbBizLinkMapper bizLinkMapper;
    private final KbRelationMapper relationMapper;
    private final KbMetricMapper metricMapper;
    private final KbBusinessConceptMapper conceptMapper;

    public KbRetrievalService(KbTableMapper tableMapper,
                              KbColumnMapper columnMapper,
                              KbIndexMapper indexMapper,
                              KbSqlTemplateMapper sqlTemplateMapper,
                              KbBizLinkMapper bizLinkMapper,
                              KbRelationMapper relationMapper,
                              KbMetricMapper metricMapper,
                              KbBusinessConceptMapper conceptMapper) {
        this.tableMapper = tableMapper;
        this.columnMapper = columnMapper;
        this.indexMapper = indexMapper;
        this.sqlTemplateMapper = sqlTemplateMapper;
        this.bizLinkMapper = bizLinkMapper;
        this.relationMapper = relationMapper;
        this.metricMapper = metricMapper;
        this.conceptMapper = conceptMapper;
    }

    /** 接口20 Schema 检索召回 */
    public KbSchemaRetrievalVO retrieveSchema(KbSchemaRetrievalDTO dto) {
        checkDs(dto.getDsId());
        List<KbTable> tables = tableMapper.selectRetrievalTables(
                dto.getDsId(), blankToNull(dto.getKeyword()), dto.getTableNames(),
                upperList(dto.getQualifiedTableNames()),
                dto.getSemanticTags(), dto.includeDraft(), dto.topK());
        List<KbColumn> columns = columnMapper.selectRetrievalColumns(
                dto.getDsId(), blankToNull(dto.getKeyword()), dto.getTableNames(),
                upperList(dto.getQualifiedTableNames()),
                dto.getSemanticTags(), dto.includeDraft(), dto.topK());

        KbSchemaRetrievalVO vo = new KbSchemaRetrievalVO();
        vo.setTables(toTableVOs(tables));
        vo.setColumns(toColumnVOs(columns));
        // 索引通道为可选能力：默认（include_indexes 未传/false）完全不查询 kb_index，
        // indexes 保持 null，保证既有响应逐字段等价（NFR-BE-004/NFR-BE-005/SC-006）。
        if (dto.includeIndexes()) {
            List<KbIndex> indexes = indexMapper.selectRetrievalIndexes(
                    dto.getDsId(), blankToNull(dto.getKeyword()), dto.getTableNames(),
                    upperList(dto.getQualifiedTableNames()),
                    dto.includeDraft(), dto.topK());
            vo.setIndexes(toIndexVOs(indexes));
        }
        return vo;
    }

    /** 接口21 SQL 模板检索召回 */
    public KbTemplateRetrievalVO retrieveTemplate(KbTemplateRetrievalDTO dto) {
        checkDs(dto.getDsId());
        List<KbSqlTemplate> templates = sqlTemplateMapper.selectRetrievalTemplates(
                dto.getDsId(), blankToNull(dto.getBizAction()), blankToNull(dto.getQuestionKeyword()),
                blankToNull(dto.getTableName()), dto.getMinConfidence(), dto.topK());
        KbTemplateRetrievalVO vo = new KbTemplateRetrievalVO();
        vo.setTemplates(toTemplateVOs(templates));
        return vo;
    }

    /** 接口22 业务链路检索召回 */
    public KbBizLinkRetrievalVO retrieveBizLink(KbBizLinkRetrievalDTO dto) {
        checkDs(dto.getDsId());
        List<KbBizLink> links = bizLinkMapper.selectRetrievalLinks(
                dto.getDsId(), blankToNull(dto.getOperationKeyword()),
                blankToNull(dto.getServiceClass()), blankToNull(dto.getTableName()), dto.topK());
        KbBizLinkRetrievalVO vo = new KbBizLinkRetrievalVO();
        vo.setLinks(toBizLinkVOs(links));
        return vo;
    }

    /** 接口23 表关系检索召回 */
    public KbRelationRetrievalVO retrieveRelation(KbRelationRetrievalDTO dto) {
        checkDs(dto.getDsId());
        ThrowUtils.throwIf((dto.getTableA() == null || dto.getTableA().isBlank())
                        && (dto.getTableB() == null || dto.getTableB().isBlank()),
                new BusinessException(ErrorCode.PARAMS_ERROR, "table_a 或 table_b 至少提供一个"));
        List<KbRelation> relations = relationMapper.selectRetrievalRelations(
                dto.getDsId(), blankToNull(dto.getTableA()), blankToNull(dto.getTableB()),
                blankToNull(dto.getRelationType()), dto.topK());
        KbRelationRetrievalVO vo = new KbRelationRetrievalVO();
        vo.setRelations(toRelationVOs(relations));
        return vo;
    }

    /** 接口24 语义知识检索召回 */
    public KbSemanticRetrievalVO retrieveSemantic(KbSemanticRetrievalDTO dto) {
        checkDs(dto.getDsId());
        String category = dto.getCategory() == null || dto.getCategory().isBlank()
                ? "ALL" : dto.getCategory().toUpperCase();
        KbSemanticRetrievalVO vo = new KbSemanticRetrievalVO();
        String keyword = blankToNull(dto.getKeyword());
        String space = blankToNull(dto.getSpaceName());
        if ("METRIC".equals(category) || "ALL".equals(category)) {
            vo.setMetrics(toMetricVOs(metricMapper.selectRetrievalMetrics(dto.getDsId(), keyword, space, dto.topK())));
        }
        if ("CONCEPT".equals(category) || "ALL".equals(category)) {
            vo.setConcepts(toConceptVOs(conceptMapper.selectRetrievalConcepts(dto.getDsId(), keyword, space, dto.topK())));
        }
        return vo;
    }

    private void checkDs(String dsId) {
        ThrowUtils.throwIf(dsId == null || dsId.isBlank(),
                new BusinessException(ErrorCode.PARAMS_ERROR, "ds_id 不能为空"));
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * 限定名归一化为 UPPER（与 kb_table/kb_column.schema_name/table_name 存储口径一致），
     * 空串/空白项剔除；全空返回 null（Mapper XML 据此跳过限定名条件，保持旧行为）。
     */
    private List<String> upperList(List<String> in) {
        if (in == null || in.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String v : in) {
            if (v != null && !v.isBlank()) {
                out.add(v.trim().toUpperCase(Locale.ROOT));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private List<KbTableVO> toTableVOs(List<KbTable> list) {
        List<KbTableVO> vos = new ArrayList<>();
        for (KbTable e : list) {
            KbTableVO vo = new KbTableVO();
            BeanUtils.copyProperties(e, vo);
            vos.add(vo);
        }
        return vos;
    }

    private List<KbColumnVO> toColumnVOs(List<KbColumn> list) {
        List<KbColumnVO> vos = new ArrayList<>();
        for (KbColumn e : list) {
            KbColumnVO vo = new KbColumnVO();
            BeanUtils.copyProperties(e, vo);
            vos.add(vo);
        }
        return vos;
    }

    /** 索引实体 → 索引候选 VO（与 toColumnVOs 同构；含 null/空集合保护） */
    private List<KbIndexVO> toIndexVOs(List<KbIndex> list) {
        List<KbIndexVO> vos = new ArrayList<>();
        if (list == null || list.isEmpty()) {
            return vos;
        }
        for (KbIndex e : list) {
            KbIndexVO vo = new KbIndexVO();
            BeanUtils.copyProperties(e, vo);
            vos.add(vo);
        }
        return vos;
    }

    private List<KbTemplateVO> toTemplateVOs(List<KbSqlTemplate> list) {
        List<KbTemplateVO> vos = new ArrayList<>();
        for (KbSqlTemplate e : list) {
            KbTemplateVO vo = new KbTemplateVO();
            BeanUtils.copyProperties(e, vo);
            vos.add(vo);
        }
        return vos;
    }

    private List<KbBizLinkVO> toBizLinkVOs(List<KbBizLink> list) {
        List<KbBizLinkVO> vos = new ArrayList<>();
        for (KbBizLink e : list) {
            KbBizLinkVO vo = new KbBizLinkVO();
            BeanUtils.copyProperties(e, vo);
            vos.add(vo);
        }
        return vos;
    }

    private List<KbRelationVO> toRelationVOs(List<KbRelation> list) {
        List<KbRelationVO> vos = new ArrayList<>();
        for (KbRelation e : list) {
            KbRelationVO vo = new KbRelationVO();
            BeanUtils.copyProperties(e, vo);
            vos.add(vo);
        }
        return vos;
    }

    private List<KbMetricVO> toMetricVOs(List<KbMetric> list) {
        List<KbMetricVO> vos = new ArrayList<>();
        for (KbMetric e : list) {
            KbMetricVO vo = new KbMetricVO();
            BeanUtils.copyProperties(e, vo);
            vos.add(vo);
        }
        return vos;
    }

    private List<KbConceptVO> toConceptVOs(List<KbBusinessConcept> list) {
        List<KbConceptVO> vos = new ArrayList<>();
        for (KbBusinessConcept e : list) {
            KbConceptVO vo = new KbConceptVO();
            BeanUtils.copyProperties(e, vo);
            vos.add(vo);
        }
        return vos;
    }
}
