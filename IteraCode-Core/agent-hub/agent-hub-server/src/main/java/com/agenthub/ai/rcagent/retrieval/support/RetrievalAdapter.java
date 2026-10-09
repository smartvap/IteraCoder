package com.agenthub.ai.rcagent.retrieval.support;

import com.agenthub.ai.base.exception.BusinessException;
import com.agenthub.ai.rcagent.kb.common.KbJsonUtils;
import com.agenthub.ai.rcagent.kb.dto.KbBizLinkRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbRelationRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSchemaRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSemanticRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbTemplateRetrievalDTO;
import com.agenthub.ai.rcagent.kb.service.KbRetrievalService;
import com.agenthub.ai.rcagent.kb.vo.KbBizLinkRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbBizLinkVO;
import com.agenthub.ai.rcagent.kb.vo.KbColumnVO;
import com.agenthub.ai.rcagent.kb.vo.KbConceptVO;
import com.agenthub.ai.rcagent.kb.vo.KbIndexVO;
import com.agenthub.ai.rcagent.kb.vo.KbMetricVO;
import com.agenthub.ai.rcagent.kb.vo.KbRelationRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbRelationVO;
import com.agenthub.ai.rcagent.kb.vo.KbSchemaRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbSemanticRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbTableVO;
import com.agenthub.ai.rcagent.kb.vo.KbTemplateRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbTemplateVO;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalChannel;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalErrorCode;
import com.agenthub.ai.rcagent.retrieval.constant.RetrievalSourceType;
import com.agenthub.ai.rcagent.retrieval.model.EvidenceRef;
import com.agenthub.ai.rcagent.retrieval.model.RetrievalHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库 A 检索适配层（RetrievalAdapter，交互层，brownfield 保护）。
 *
 * <p>只读包装 module-004 {@link KbRetrievalService} 五路召回（实际方法为
 * retrieveSchema/retrieveTemplate/retrieveBizLink/retrieveRelation/retrieveSemantic，
 * 见 kb-storage-review api-contract 接口 20~24），把 kb 模块 VO 映射为统一 {@link RetrievalHit}。
 * A 库内部检索失败/超时统一抛 42103（A 路为事实主干，不静默吞掉，spec NFR-BE-003）；
 * 概念回填 related_columns 在本层做 schema 列级补召回（FR-BE-001）；related_sql_ids 因
 * module-004 KbTemplateRetrievalDTO 无 SQL id 入参不在本层消费（契约扩展需用户确认，
 * 见 searchARoutes javadoc）。
 * 注意：module-004 实际类方法名为 retrieve* 而非 SDD 占位的 search*，此处以实际类为准。</p>
 */
@Slf4j
@Component
public class RetrievalAdapter {

    private static final int EXCERPT_MAX = 200;

    /** 概念回填 related_columns 单列补召回上限（避免多次 schema 检索拖慢 P95，NFR-BE-001） */
    private static final int RELATED_COLUMN_BOOST_LIMIT = 5;

    /**
     * 命中表列展开的 topK 下限。
     *
     * <p>主 schema 检索沿用 per_channel_top_k（默认 5）会截断列清单，导致「表已命中但列缺失」，
     * 生成模型只能猜列/避列（问题取证：workflow_metadata.status 未被召回）。此处强制放宽到 50，
     * 上限受 {@link KbSchemaRetrievalDTO#topK()} 的 100 约束。</p>
     */
    private static final int TABLE_COLUMN_EXPAND_LIMIT = 50;

    /**
     * 索引命中单表上限：防止宽表索引数极多时淹没列清单/上下文（FR-BE-040、A-3.8）。
     */
    private static final int INDEX_HIT_LIMIT = 20;

    private final KbRetrievalService kbRetrievalService;

    public RetrievalAdapter(KbRetrievalService kbRetrievalService) {
        this.kbRetrievalService = kbRetrievalService;
    }

    /** 语义路召回（供 KnowledgeConceptResolver 回填复用） */
    public List<RetrievalHit> searchSemantic(String dsId, String keyword, boolean includeDraft, int topK) {
        try {
            KbSemanticRetrievalDTO semanticDto = new KbSemanticRetrievalDTO();
            semanticDto.setDsId(dsId);
            semanticDto.setKeyword(keyword);
            semanticDto.setTopK(topK);
            KbSemanticRetrievalVO vo = kbRetrievalService.retrieveSemantic(semanticDto);
            return vo == null ? new ArrayList<>() : toHits(vo);
        } catch (BusinessException e) {
            throw wrapKbA(e);
        } catch (Exception e) {
            log.warn("retrieval: 知识库 A semantic 检索异常: {}", e.getMessage());
            throw new BusinessException(RetrievalErrorCode.KB_A_FAILED, "知识库A检索失败");
        }
    }

    /** 语义召回 VO → 统一命中（供 KnowledgeConceptResolver 在模块边界复用） */
    public List<RetrievalHit> semanticHitsOf(KbSemanticRetrievalVO vo) {
        return vo == null ? new ArrayList<>() : toHits(vo);
    }

    /**
     * A 库五路召回整合（semantic 路命中可外部传入避免重复检索）。
     *
     * <p>related_tables 已用于 schema 精确表名过滤与 relation 表对配对；
     * related_columns 在本层做 schema 列级补召回（FR-BE-001 字段候选放大）；
     * related_sql_ids 因 module-004 KbTemplateRetrievalDTO 无 SQL id 入参，
     * 当前仅在 KnowledgeConceptResolver 保留解析结果，不做契约扩展（受限原因：依赖模块交互层变更需用户确认）。</p>
     *
     * @param dsId            数据源实例 ID
     * @param keyword         主关键词（问题/自由词）
     * @param relatedTables   概念回填的表名候选（可空，schema 精确表名过滤）
     * @param relatedColumns  概念回填的字段候选（可空，schema 列级补召回）
     * @param questionTokens  问题中提取的标识符 token（可空，命中表列展开时按需只展开问题点名的列）
     * @param includeDraft    是否含草稿
     * @param topK            每路条数
     * @param semanticHits    已由 KnowledgeConceptResolver 语义路召回的统一命中（可空，为空时内部再召回一次）
     */
    public Map<RetrievalChannel, List<RetrievalHit>> searchARoutes(String dsId, String keyword,
                                                                   List<String> relatedTables, List<String> relatedColumns,
                                                                   List<String> questionTokens,
                                                                   boolean includeDraft, int topK,
                                                                   List<RetrievalHit> semanticHits) {
        Map<RetrievalChannel, List<RetrievalHit>> routes = new LinkedHashMap<>();
        try {
            // schema 路（表 + 字段）：relatedTables 作为精确表名过滤
            KbSchemaRetrievalDTO schemaDto = new KbSchemaRetrievalDTO();
            schemaDto.setDsId(dsId);
            schemaDto.setKeyword(keyword);
            schemaDto.setTableNames(relatedTables == null || relatedTables.isEmpty() ? null : relatedTables);
            schemaDto.setIncludeDraft(includeDraft);
            schemaDto.setTopK(topK);
            KbSchemaRetrievalVO schemaVo = kbRetrievalService.retrieveSchema(schemaDto);
            routes.put(RetrievalChannel.SCHEMA_TABLES, new ArrayList<>());
            routes.put(RetrievalChannel.SCHEMA_COLUMNS, new ArrayList<>());
            // 索引通道：由命中表列展开的同一次追加检索装配（含 schema_indexes 统计键初值）
            routes.put(RetrievalChannel.SCHEMA_INDEXES, new ArrayList<>());
            if (schemaVo != null) {
                toHits(schemaVo, routes);
                // 命中表列展开：把命中表的真实列清单补进上下文（FR-BE-001 前的必需要素，
                // 否则生成模型不知表有哪些列，会猜列/避列）；问题点名了列时只展开这些列
                expandColumnsOfHitTables(dsId, schemaVo, includeDraft, topK, questionTokens, routes);
            }
            // 概念回填字段候选放大：relatedColumns 未覆盖到的列名逐个补召回（列名/注释模糊）
            boostRelatedColumnHits(dsId, relatedTables, includeDraft, topK, relatedColumns, routes);

            // template 路
            KbTemplateRetrievalDTO templateDto = new KbTemplateRetrievalDTO();
            templateDto.setDsId(dsId);
            templateDto.setQuestionKeyword(keyword);
            templateDto.setTopK(topK);
            KbTemplateRetrievalVO templateVo = kbRetrievalService.retrieveTemplate(templateDto);
            routes.put(RetrievalChannel.TEMPLATES, new ArrayList<>());
            if (templateVo != null) {
                routes.get(RetrievalChannel.TEMPLATES).addAll(toHits(templateVo));
            }

            // biz-link 路
            KbBizLinkRetrievalDTO bizDto = new KbBizLinkRetrievalDTO();
            bizDto.setDsId(dsId);
            bizDto.setOperationKeyword(keyword);
            bizDto.setTopK(topK);
            KbBizLinkRetrievalVO bizVo = kbRetrievalService.retrieveBizLink(bizDto);
            routes.put(RetrievalChannel.BIZ_LINKS, new ArrayList<>());
            if (bizVo != null) {
                routes.get(RetrievalChannel.BIZ_LINKS).addAll(toHits(bizVo));
            }

            // relation 路（至少一个表名才调用，避免无意义全量）
            routes.put(RetrievalChannel.RELATIONS, new ArrayList<>());
            if (relatedTables != null && !relatedTables.isEmpty()) {
                KbRelationRetrievalDTO relationDto = new KbRelationRetrievalDTO();
                relationDto.setDsId(dsId);
                relationDto.setTableA(relatedTables.get(0));
                if (relatedTables.size() > 1) {
                    relationDto.setTableB(relatedTables.get(1));
                }
                relationDto.setTopK(topK);
                KbRelationRetrievalVO relationVo = kbRetrievalService.retrieveRelation(relationDto);
                if (relationVo != null) {
                    routes.get(RetrievalChannel.RELATIONS).addAll(toHits(relationVo));
                }
            }

            // semantic 路（指标 + 概念）：外部传入命中则直接使用，否则内部召回
            if (semanticHits != null && !semanticHits.isEmpty()) {
                routes.put(RetrievalChannel.METRICS, new ArrayList<>());
                routes.put(RetrievalChannel.CONCEPTS, new ArrayList<>());
                for (RetrievalHit hit : semanticHits) {
                    routes.get(RetrievalSourceType.A_METRIC.equals(hit.getSource())
                            ? RetrievalChannel.METRICS : RetrievalChannel.CONCEPTS).add(hit);
                }
            } else {
                List<RetrievalHit> semantic = searchSemantic(dsId, keyword, includeDraft, topK);
                routes.put(RetrievalChannel.METRICS, new ArrayList<>());
                routes.put(RetrievalChannel.CONCEPTS, new ArrayList<>());
                for (RetrievalHit hit : semantic) {
                    routes.get(RetrievalSourceType.A_METRIC.equals(hit.getSource())
                            ? RetrievalChannel.METRICS : RetrievalChannel.CONCEPTS).add(hit);
                }
            }
            return routes;
        } catch (BusinessException e) {
            throw wrapKbA(e);
        } catch (Exception e) {
            log.warn("retrieval: 知识库 A 检索异常: {}", e.getMessage());
            throw new BusinessException(RetrievalErrorCode.KB_A_FAILED, "知识库A检索失败");
        }
    }

    /** module-004 业务异常包装为 42103（非 42103 自身） */
    private BusinessException wrapKbA(BusinessException e) {
        if (e.getCode() == RetrievalErrorCode.KB_A_FAILED) {
            return e;
        }
        log.warn("retrieval: 知识库 A 检索失败: code={} msg={}", e.getCode(), e.getMessage());
        return new BusinessException(RetrievalErrorCode.KB_A_FAILED, "知识库A检索失败");
    }

    /**
     * 命中表列清单展开（问题取证修复：表命中但列缺失）。
     *
     * <p>主 schema 检索的 keyword 是单一 LIKE 子串，只命中表名/注释含该词的列，且受 per_channel_top_k
     * 截断，常常「表召回成功而该表列一条不回」（取证：A 路 schema_columns=1 命中的是别的表的列）。
     * 这里在主 schema 命中后，用命中表名做精确过滤再检索一次列（keyword 置空、topK 放宽到
     * {@link #TABLE_COLUMN_EXPAND_LIMIT}），把真实列清单（如 workflow_metadata.status 类型:varchar(32)）
     * 并入 SCHEMA_COLUMNS 通道，按「表名.列名」小写归一去重（与 {@link #boostRelatedColumnHits} 同口径）。</p>
     *
     * <p><b>按需展开</b>：若 {@code questionTokens} 中有点名该表的某列（大小写不敏感命中列名），
     * 只把点名的列并入列通道（避免「只问两列却返回整表全部列」）；点名列集合为空（未点名/查数场景）
     * 时维持全列展开，供生成模型了解整表列结构。</p>
     *
     * <p>该追加查询属于增强，失败不得影响已成功的主 schema 召回。</p>
     */
    private void expandColumnsOfHitTables(String dsId, KbSchemaRetrievalVO schemaVo,
                                          boolean includeDraft, int topK,
                                          List<String> questionTokens,
                                          Map<RetrievalChannel, List<RetrievalHit>> routes) {
        List<RetrievalHit> columnHits = routes.get(RetrievalChannel.SCHEMA_COLUMNS);
        if (schemaVo == null || schemaVo.getTables() == null || schemaVo.getTables().isEmpty()
                || columnHits == null) {
            return;
        }
        // 命中表名：去空、按小写去重、保持返回顺序
        List<String> hitTableNames = new ArrayList<>();
        Set<String> seenTables = new HashSet<>();
        for (KbTableVO t : schemaVo.getTables()) {
            if (t == null || t.getTableName() == null || t.getTableName().isBlank()) {
                continue;
            }
            String tableName = t.getTableName().trim();
            if (seenTables.add(tableName.toLowerCase())) {
                hitTableNames.add(tableName);
            }
        }
        if (hitTableNames.isEmpty()) {
            return;
        }
        // 已召回列（含主 schema 列）「表名.列名」小写归一，避免重复并入
        Set<String> covered = new HashSet<>();
        for (RetrievalHit hit : columnHits) {
            if (hit.getTitle() != null) {
                covered.add(hit.getTitle().trim().toLowerCase());
            }
        }
        // 问题点名的候选列名（小写），用于判定是否按需只展开这些列
        Set<String> questionTokenLower = new HashSet<>();
        if (questionTokens != null) {
            for (String token : questionTokens) {
                if (token != null && !token.isBlank()) {
                    questionTokenLower.add(token.trim().toLowerCase());
                }
            }
        }
        try {
            KbSchemaRetrievalDTO dto = new KbSchemaRetrievalDTO();
            dto.setDsId(dsId);
            // keyword 置空：仅按 tableNames 精确过滤（mapper 支持 tableNames 独立生效）
            dto.setKeyword(null);
            dto.setTableNames(hitTableNames);
            dto.setIncludeDraft(includeDraft);
            // 放宽 topK，避免主检索的 topK=5 把列清单截断
            dto.setTopK(Math.max(topK, TABLE_COLUMN_EXPAND_LIMIT));
            // 同一次追加检索顺带取命中表索引（include_indexes=true）；未命中表时由 toIndexHits 过滤
            dto.setIncludeIndexes(true);
            KbSchemaRetrievalVO vo = kbRetrievalService.retrieveSchema(dto);
            if (vo == null) {
                return;
            }
            if (vo.getColumns() != null) {
                // 按需判定：问题点名的 token 命中任一命中表列名（大小写不敏感）→ 只展开这些列
                Set<String> namedColumns = new HashSet<>();
                if (!questionTokenLower.isEmpty()) {
                    for (KbColumnVO c : vo.getColumns()) {
                        if (c == null || c.getColumnName() == null || c.getColumnName().isBlank()) {
                            continue;
                        }
                        String columnLower = c.getColumnName().trim().toLowerCase();
                        if (questionTokenLower.contains(columnLower)) {
                            namedColumns.add(columnLower);
                        }
                    }
                }
                boolean expandNamedOnly = !namedColumns.isEmpty();
                for (KbColumnVO c : vo.getColumns()) {
                    if (c == null || c.getColumnName() == null) {
                        continue;
                    }
                    if (expandNamedOnly && !namedColumns.contains(c.getColumnName().trim().toLowerCase())) {
                        // 问题点名了列：未点名的列不展开（避免上下文被整表列淹没）
                        continue;
                    }
                    String title = (c.getTableName() == null ? "" : c.getTableName() + ".") + c.getColumnName();
                    if (!covered.add(title.trim().toLowerCase())) {
                        continue;
                    }
                    columnHits.add(toHit(
                            RetrievalSourceType.A_SCHEMA_COLUMN, RetrievalChannel.SCHEMA_COLUMNS,
                            c.getId(), title, buildColumnContent(c), c.getConfidence(),
                            c.getVerified(), c.getEvidence(), pathOf("column", title)));
                }
            }
            // 索引装配：只取命中表的索引（与列同源、同一次检索结果）
            toIndexHits(vo, hitTableNames, routes);
        } catch (Exception e) {
            // 追加查询/索引装配失败不阻断（主 schema 召回已成功）；脱敏：只记命中表数量，不记问题原文
            log.debug("retrieval: 命中表列/索引展开跳过 tables={}", hitTableNames.size());
        }
    }

    /**
     * 命中表索引装配（与命中表列展开同一次追加检索结果）。
     *
     * <p>只装配命中表的索引（title 形如「表名.索引名」，前缀匹配命中表名），
     * 单表受 {@link #INDEX_HIT_LIMIT} 上限约束防止宽表索引淹没列清单（FR-BE-040、A-3.8）；
     * 渲染严格按 data-model §7.4 逐字符契约（见 {@link #buildIndexContent}）。
     * 该装配属增强：异常由调用方 catch 后仅记 DEBUG，不阻断已成功的主 schema 召回（FR-BE-041）。</p>
     */
    private void toIndexHits(KbSchemaRetrievalVO vo, List<String> hitTableNames,
                             Map<RetrievalChannel, List<RetrievalHit>> routes) {
        if (vo == null || vo.getIndexes() == null || vo.getIndexes().isEmpty()) {
            return;
        }
        List<RetrievalHit> indexHits = routes.get(RetrievalChannel.SCHEMA_INDEXES);
        if (indexHits == null) {
            return;
        }
        // 命中表名小写归一（title 前缀匹配「表名.」用）
        Set<String> hitTableLower = new HashSet<>();
        if (hitTableNames != null) {
            for (String tableName : hitTableNames) {
                if (tableName != null && !tableName.isBlank()) {
                    hitTableLower.add(tableName.trim().toLowerCase());
                }
            }
        }
        if (hitTableLower.isEmpty()) {
            return;
        }
        // 已装配索引 title 小写归一，避免重复并入
        Set<String> covered = new HashSet<>();
        for (RetrievalHit hit : indexHits) {
            if (hit.getTitle() != null) {
                covered.add(hit.getTitle().trim().toLowerCase());
            }
        }
        // 单表命中计数（表中无索引名/非命中表的行不计入）
        Map<String, Integer> perTableCount = new HashMap<>();
        for (KbIndexVO index : vo.getIndexes()) {
            if (index == null || index.getIndexName() == null || index.getIndexName().isBlank()) {
                continue;
            }
            String tableName = index.getTableName() == null ? "" : index.getTableName().trim();
            String title = (tableName.isEmpty() ? "" : tableName + ".") + index.getIndexName();
            String titleLower = title.trim().toLowerCase();
            if (!hitTableLower.contains(tableName.toLowerCase())) {
                // 非命中表的索引不装配（title 前缀匹配「表名.」）
                continue;
            }
            if (!covered.add(titleLower)) {
                continue;
            }
            String tableLower = tableName.toLowerCase();
            int used = perTableCount.getOrDefault(tableLower, 0);
            if (used >= INDEX_HIT_LIMIT) {
                continue;
            }
            perTableCount.put(tableLower, used + 1);
            // KbIndexVO 无 evidence 字段，证据由 path（index/表名.索引名）+ 来源标注承担
            indexHits.add(toHit(
                    RetrievalSourceType.A_SCHEMA_INDEX, RetrievalChannel.SCHEMA_INDEXES,
                    index.getId(), title, buildIndexContent(index), index.getConfidence(),
                    index.getVerified(), null, pathOf("index", title)));
        }
    }

    /**
     * 索引命中渲染（data-model §7.4 逐字符契约）：
     * {@code 索引:{indexName} 列:{c1,c2} 唯一:{是|否} 主键:{是|否}[ 类型:{indexType}]}。
     * 空列清单输出 {@code 列:-}；indexType 为 null/空白不输出类型段。
     */
    private String buildIndexContent(KbIndexVO index) {
        List<String> columns = parseIndexColumns(index.getIndexColumns());
        StringBuilder sb = new StringBuilder();
        sb.append("索引:").append(index.getIndexName());
        sb.append(" 列:").append(columns.isEmpty() ? "-" : String.join(",", columns));
        sb.append(" 唯一:").append(isFlagOn(index.getIsUnique()) ? "是" : "否");
        sb.append(" 主键:").append(isFlagOn(index.getIsPrimary()) ? "是" : "否");
        if (index.getIndexType() != null && !index.getIndexType().isBlank()) {
            sb.append(" 类型:").append(index.getIndexType());
        }
        return sb.toString();
    }

    /** 索引列清单 JSON 数组文本 → List&lt;String&gt;；null/空白/非法均返回空列表（渲染为 列:-） */
    private List<String> parseIndexColumns(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<String> columns = KbJsonUtils.parseStringList(json);
            return columns == null ? new ArrayList<>() : new ArrayList<>(columns);
        } catch (Exception e) {
            // 列清单文本非法只影响本条渲染，不影响其余索引命中（降级为 列:-）
            return new ArrayList<>();
        }
    }

    /** 0/1 标记判定：null 视同 0（不输出标记） */
    private static boolean isFlagOn(Integer flag) {
        return flag != null && flag == 1;
    }

    /**
     * 概念回填 related_columns 列级补召回（FR-BE-001：字段候选放大）。
     *
     * <p>module-004 KbSchemaRetrievalDTO 的 keyword 为整句 LIKE 匹配，无法一次表达多个列名；
     * 这里对主 schema 召回未覆盖到的 relatedColumns 逐个以列名作为 keyword 追加一次 schema 检索
     * （沿用 tableNames 精确过滤），命中列并入 SCHEMA_COLUMNS 通道并按 (title, id) 去重。
     * 单次补召回数量受 {@link #RELATED_COLUMN_BOOST_LIMIT} 限制，避免拖慢 P95。
     * 补召回失败仅记录，不阻断已成功的主 schema 召回。</p>
     */
    private void boostRelatedColumnHits(String dsId, List<String> tableNames,
                                        boolean includeDraft, int topK,
                                        List<String> relatedColumns,
                                        Map<RetrievalChannel, List<RetrievalHit>> routes) {
        if (relatedColumns == null || relatedColumns.isEmpty()) {
            return;
        }
        List<RetrievalHit> columnHits = routes.get(RetrievalChannel.SCHEMA_COLUMNS);
        if (columnHits == null) {
            return;
        }
        // 已召回列的 title（表名.列名）小写归一，避免重复并入
        Set<String> covered = new HashSet<>();
        for (RetrievalHit hit : columnHits) {
            if (hit.getTitle() != null) {
                covered.add(hit.getTitle().trim().toLowerCase());
            }
        }
        int boosted = 0;
        for (String relatedColumn : relatedColumns) {
            if (boosted >= RELATED_COLUMN_BOOST_LIMIT) {
                break;
            }
            if (relatedColumn == null || relatedColumn.isBlank()) {
                continue;
            }
            String columnName = relatedColumn.trim();
            // related_columns 可能存在 "表名.列名" 格式，取末段列名
            int dot = columnName.lastIndexOf('.');
            if (dot >= 0 && dot < columnName.length() - 1) {
                columnName = columnName.substring(dot + 1);
            }
            boosted++;
            try {
                KbSchemaRetrievalDTO dto = new KbSchemaRetrievalDTO();
                dto.setDsId(dsId);
                dto.setKeyword(columnName);
                dto.setTableNames(tableNames == null || tableNames.isEmpty() ? null : tableNames);
                dto.setIncludeDraft(includeDraft);
                dto.setTopK(topK);
                KbSchemaRetrievalVO vo = kbRetrievalService.retrieveSchema(dto);
                if (vo == null || vo.getColumns() == null) {
                    continue;
                }
                for (KbColumnVO c : vo.getColumns()) {
                    if (c == null || c.getColumnName() == null) {
                        continue;
                    }
                    String title = (c.getTableName() == null ? "" : c.getTableName() + ".") + c.getColumnName();
                    if (!covered.add(title.trim().toLowerCase())) {
                        continue;
                    }
                    columnHits.add(toHit(
                            RetrievalSourceType.A_SCHEMA_COLUMN, RetrievalChannel.SCHEMA_COLUMNS,
                            c.getId(), title, buildColumnContent(c), c.getConfidence(),
                            c.getVerified(), c.getEvidence(), pathOf("column", title)));
                }
            } catch (Exception e) {
                // 补召回失败不阻断（主 schema 召回已成功）
                log.debug("retrieval: related_columns 补召回跳过 col={}", columnName);
            }
        }
    }

    // ============ VO → RetrievalHit ============

    private void toHits(KbSchemaRetrievalVO vo, Map<RetrievalChannel, List<RetrievalHit>> routes) {
        if (vo.getTables() != null) {
            for (KbTableVO t : vo.getTables()) {
                routes.get(RetrievalChannel.SCHEMA_TABLES).add(toHit(
                        RetrievalSourceType.A_SCHEMA_TABLE, RetrievalChannel.SCHEMA_TABLES,
                        t.getId(), t.getTableName(), buildTableContent(t), t.getConfidence(),
                        t.getVerified(), t.getEvidence(), pathOf("table", t.getTableName())));
            }
        }
        if (vo.getColumns() != null) {
            for (KbColumnVO c : vo.getColumns()) {
                String title = (c.getTableName() == null ? "" : c.getTableName() + ".") + c.getColumnName();
                routes.get(RetrievalChannel.SCHEMA_COLUMNS).add(toHit(
                        RetrievalSourceType.A_SCHEMA_COLUMN, RetrievalChannel.SCHEMA_COLUMNS,
                        c.getId(), title, buildColumnContent(c), c.getConfidence(),
                        c.getVerified(), c.getEvidence(), pathOf("column", title)));
            }
        }
    }

    private List<RetrievalHit> toHits(KbTemplateRetrievalVO vo) {
        List<RetrievalHit> hits = new ArrayList<>();
        if (vo.getTemplates() != null) {
            for (KbTemplateVO t : vo.getTemplates()) {
                hits.add(toHit(RetrievalSourceType.A_TEMPLATE, RetrievalChannel.TEMPLATES,
                        t.getId(), t.getBizAction(), buildTemplateContent(t), t.getConfidence(),
                        t.getVerified(), t.getEvidence(), pathOf("template", t.getBizAction())));
            }
        }
        return hits;
    }

    private List<RetrievalHit> toHits(KbBizLinkRetrievalVO vo) {
        List<RetrievalHit> hits = new ArrayList<>();
        if (vo.getLinks() != null) {
            for (KbBizLinkVO b : vo.getLinks()) {
                hits.add(toHit(RetrievalSourceType.A_BIZ_LINK, RetrievalChannel.BIZ_LINKS,
                        b.getId(), b.getBizOperation(), buildLinkContent(b), b.getConfidence(),
                        b.getVerified(), b.getEvidence(), pathOf("biz-link", b.getBizOperation())));
            }
        }
        return hits;
    }

    private List<RetrievalHit> toHits(KbRelationRetrievalVO vo) {
        List<RetrievalHit> hits = new ArrayList<>();
        if (vo.getRelations() != null) {
            for (KbRelationVO r : vo.getRelations()) {
                String title = r.getTableA() + "." + (r.getColumnA() == null ? "" : r.getColumnA())
                        + "=" + r.getTableB() + "." + (r.getColumnB() == null ? "" : r.getColumnB());
                hits.add(toHit(RetrievalSourceType.A_RELATION, RetrievalChannel.RELATIONS,
                        r.getId(), title, buildRelationContent(r), r.getConfidence(),
                        r.getVerified(), r.getEvidence(), pathOf("relation", title)));
            }
        }
        return hits;
    }

    private List<RetrievalHit> toHits(KbSemanticRetrievalVO vo) {
        List<RetrievalHit> hits = new ArrayList<>();
        if (vo.getMetrics() != null) {
            for (KbMetricVO m : vo.getMetrics()) {
                hits.add(toHit(RetrievalSourceType.A_METRIC, RetrievalChannel.METRICS,
                        m.getId(), m.getMetricName(), buildMetricContent(m), m.getConfidence(),
                        m.getVerified(), m.getEvidence(), pathOf("metric", m.getMetricName())));
            }
        }
        if (vo.getConcepts() != null) {
            for (KbConceptVO c : vo.getConcepts()) {
                hits.add(toHit(RetrievalSourceType.A_CONCEPT, RetrievalChannel.CONCEPTS,
                        c.getId(), c.getConceptName(), buildConceptContent(c), c.getConfidence(),
                        c.getVerified(), c.getEvidence(), pathOf("concept", c.getConceptName())));
            }
        }
        return hits;
    }

    private RetrievalHit toHit(RetrievalSourceType source, RetrievalChannel channel, Long rowId,
                               String title, String content, BigDecimal confidence, Integer verified,
                               String evidence, String path) {
        RetrievalHit hit = new RetrievalHit();
        hit.setSource(source);
        hit.setChannel(channel.getValue());
        hit.setKbId(rowId == null ? null : String.valueOf(rowId));
        hit.setRowId(rowId);
        hit.setTitle(title == null ? "" : title);
        hit.setContent(content == null ? null : truncate(content, 500));
        hit.setConfidence(confidence == null ? BigDecimal.ZERO : confidence);
        hit.setVerified(verified == null ? 0 : verified);
        // BR-004 证据可溯源：kb evidence 文本截断摘录；path 帮助定位
        hit.setEvidence(List.of(EvidenceRef.of(source.getValue(), String.valueOf(rowId),
                path, evidence == null ? null : truncate(evidence, EXCERPT_MAX))));
        hit.setSemanticFingerprint(FusionMerger.normalizeFingerprint(title));
        hit.setConflictFlag("NONE");
        return hit;
    }

    private String buildTableContent(KbTableVO t) {
        StringBuilder sb = new StringBuilder();
        if (t.getCommentText() != null) {
            sb.append(t.getCommentText());
        }
        if (t.getSemanticTags() != null && !t.getSemanticTags().isBlank()) {
            sb.append(" 语义标签:").append(t.getSemanticTags());
        }
        return sb.toString();
    }

    private String buildColumnContent(KbColumnVO c) {
        StringBuilder sb = new StringBuilder();
        if (c.getCommentText() != null) {
            sb.append(c.getCommentText());
        }
        if (c.getDataType() != null) {
            sb.append(" 类型:").append(c.getDataType());
        }
        // data-model §7.4 逐字符契约：只追加、不改既有片段；顺序固定「主键在前、外键在后」，
        // 两者同时命中输出「 [主键] [外键]」；null 视同 0
        if (isFlagOn(c.getIsPk())) {
            sb.append(" [主键]");
        }
        if (isFlagOn(c.getIsFk())) {
            sb.append(" [外键]");
        }
        return sb.toString();
    }

    private String buildTemplateContent(KbTemplateVO t) {
        StringBuilder sb = new StringBuilder();
        if (t.getNormalizedSql() != null) {
            sb.append("SQL:").append(t.getNormalizedSql());
        }
        if (t.getSampleQuestions() != null && !t.getSampleQuestions().isBlank()) {
            sb.append(" 示例问题:").append(t.getSampleQuestions());
        }
        return sb.toString();
    }

    private String buildLinkContent(KbBizLinkVO b) {
        StringBuilder sb = new StringBuilder();
        if (b.getServiceClass() != null) {
            sb.append("service:").append(b.getServiceClass());
        }
        if (b.getDaoClass() != null) {
            sb.append(" dao:").append(b.getDaoClass());
        }
        return sb.toString();
    }

    private String buildRelationContent(KbRelationVO r) {
        StringBuilder sb = new StringBuilder();
        if (r.getRelationType() != null) {
            sb.append("关系类型:").append(r.getRelationType());
        }
        if (r.getJoinFields() != null) {
            sb.append(" join:").append(r.getJoinFields());
        }
        return sb.toString();
    }

    private String buildMetricContent(KbMetricVO m) {
        StringBuilder sb = new StringBuilder();
        if (m.getDefinition() != null) {
            sb.append(m.getDefinition());
        }
        if (m.getCalculationRule() != null) {
            sb.append(" 口径:").append(m.getCalculationRule());
        }
        return sb.toString();
    }

    private String buildConceptContent(KbConceptVO c) {
        StringBuilder sb = new StringBuilder();
        if (c.getDefinition() != null) {
            sb.append(c.getDefinition());
        }
        if (c.getSynonyms() != null && !c.getSynonyms().isBlank()) {
            sb.append(" 同义词:").append(c.getSynonyms());
        }
        return sb.toString();
    }

    private String pathOf(String type, String name) {
        return type + "/" + (name == null ? "" : name);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
