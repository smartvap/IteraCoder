package com.agenthub.ai.rcagent.engine.support;

import com.agenthub.ai.rcagent.engine.node.RcaNodeUtils;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataQuestionDetector.MetadataCategory;
import com.agenthub.ai.rcagent.kb.dto.KbRelationRetrievalDTO;
import com.agenthub.ai.rcagent.kb.dto.KbSchemaRetrievalDTO;
import com.agenthub.ai.rcagent.kb.service.KbRetrievalService;
import com.agenthub.ai.rcagent.kb.vo.KbColumnVO;
import com.agenthub.ai.rcagent.kb.vo.KbIndexVO;
import com.agenthub.ai.rcagent.kb.vo.KbRelationRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbRelationVO;
import com.agenthub.ai.rcagent.kb.vo.KbSchemaRetrievalVO;
import com.agenthub.ai.rcagent.kb.vo.KbTableVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RCAgent 元数据维度化字典直查组件（rca1.5.0.0 metadata-evidence-lookup）。
 *
 * <p>背景（真实故障）：提问「某表的索引有哪些？」时，通用检索把索引候选排在最低来源优先级，
 * 经 {@code topK=10} 融合截断后索引 100% 被挤出，答题节点拿不到索引证据。本组件按
 * {@link RcaMetadataQuestionDetector} 判定的**问题所问维度**，带 {@code ds_id} + 表名条件
 * **直查字典**（绕过通用截断），产出结构化读模型供渲染层追加为证据段。</p>
 *
 * <p>职责：① 表名解析（问题 token 经字典验证 → 回退最长 token 检索命中表）；② 维度化带条件直查；
 * ③ Java 层维度过滤（既有 Mapper 无 {@code is_primary/is_unique/is_pk/is_fk} 过滤参数，且禁止改 SQL/XML）；
 * ④ 产出不可变读模型。全部只读，零 DDL、零落库、零字典写入。</p>
 *
 * <p>降级契约：{@code dsId} 空白 / 问题空白 / 维度为 null / 表名解析为空 / 字典服务异常
 * 一律返回空结果（不抛异常、不阻断主链路）；表已解析但维度无行 ⇒ 返回非空结果 + 如实说明 note。</p>
 *
 * <p>安全：日志仅输出维度/来源/计数（category/tableSource/tableCount/hitCount），
 * 禁止输出问题全文与 JWT/Token/API Key/数据库账号密码/连接串（AGENTS.md 4.3/5.4）。</p>
 */
@Slf4j
@Component
public class RcaMetadataDictionaryLookup {

    /** 标识符 token（复刻 {@code RcaRetrievalNode.IDENTIFIER_TOKEN} 口径）：首字符字母/下划线，后接字母/数字/下划线 */
    private static final Pattern IDENTIFIER_TOKEN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** token 数量上限（复刻 {@code RcaRetrievalNode.IDENTIFIER_TOKEN_LIMIT}） */
    static final int IDENTIFIER_TOKEN_LIMIT = 10;
    /** 命中表上限：保证单次元数据问题的字典查询次数 ≤ 4（3 次 schema + 1 次 relation） */
    static final int MAX_TABLES = 3;
    /** 字典查询 topK 上限（KbSchemaRetrievalDTO.topK 上限 100），规避查询级 LIMIT 截断 */
    static final int TOP_K = 100;

    /** 表名来源：问题点名（token 经 kb_table 验证） */
    public static final String TABLE_SOURCE_QUESTION_TOKEN = "QUESTION_TOKEN";
    /** 表名来源：本轮检索命中的 schema 表名（最长 token 作关键词语义回退） */
    public static final String TABLE_SOURCE_RETRIEVAL_HIT = "RETRIEVAL_HIT";
    /** 表名来源：未解析到表名（降级不产出证据） */
    public static final String TABLE_SOURCE_NONE = "NONE";

    /** 证据行类型：索引行 */
    public static final String KIND_INDEX = "INDEX";
    /** 证据行类型：列行 */
    public static final String KIND_COLUMN = "COLUMN";
    /** 证据行类型：表行 */
    public static final String KIND_TABLE = "TABLE";
    /** 证据行类型：关系行 */
    public static final String KIND_RELATION = "RELATION";
    /** 证据行类型：如实说明行 */
    public static final String KIND_NOTE = "NOTE";

    /** 约束维度如实说明（kb_column 无「可空/非空」字段，禁止编造 NOT NULL） */
    private static final String HINT_CONSTRAINT = "元数据字典未收录非空约束明细";

    /** 降级原因：ds_id 空白（零字典查询，G-06） */
    private static final String REASON_DS_ID_EMPTY = "DS_ID_EMPTY";
    /** 降级原因：问题为空 */
    private static final String REASON_QUESTION_EMPTY = "QUESTION_EMPTY";
    /** 降级原因：维度为 null */
    private static final String REASON_CATEGORY_NULL = "CATEGORY_NULL";
    /** 降级原因：维度规格缺失（G-03：不抛异常，降级不产出） */
    private static final String REASON_SPEC_MISSING = "DIMENSION_SPEC_MISSING";

    /** 维度直查规格表（data-model §1：维度 → 字典表 + Java 层过滤 + 行类型 + 空结果文案），无状态常量映射 */
    private static final Map<MetadataCategory, DimSpec> DIMENSION_SPECS = buildDimensionSpecs();

    private final KbRetrievalService kbRetrievalService;

    public RcaMetadataDictionaryLookup(KbRetrievalService kbRetrievalService) {
        this.kbRetrievalService = kbRetrievalService;
    }

    /**
     * 元数据字典直查主入口（永不为 null）。
     *
     * @param dsId     数据源 ID（强制隔离维度；空白 ⇒ 零查询、不产出证据）
     * @param question 用户问题（用于表名解析；空白 ⇒ 不产出证据）
     * @param category 问题所问维度（来自判定器；null ⇒ 不产出证据）
     * @return 直查结果；失败/无法定位表一律为空结果（不抛异常）
     */
    public LookupResult lookup(String dsId, String question, MetadataCategory category) {
        if (isBlank(dsId)) {
            return LookupResult.empty(category, TABLE_SOURCE_NONE, REASON_DS_ID_EMPTY);
        }
        if (category == null) {
            return LookupResult.empty(null, TABLE_SOURCE_NONE, REASON_CATEGORY_NULL);
        }
        if (isBlank(question)) {
            return LookupResult.empty(category, TABLE_SOURCE_NONE, REASON_QUESTION_EMPTY);
        }
        DimSpec spec = DIMENSION_SPECS.get(category);
        if (spec == null) {
            // G-03：新增枚举值未配规格时不得抛异常，降级为不产出证据
            return LookupResult.empty(category, TABLE_SOURCE_NONE, REASON_SPEC_MISSING);
        }
        try {
            TableResolution resolution = resolveTables(dsId, question);
            if (spec.requiresTableName() && resolution.tables().isEmpty()) {
                // G-05：无表名必降级，严禁无表名全表扫描取回无关字典
                return LookupResult.empty(category, TABLE_SOURCE_NONE, spec.emptyHint());
            }
            List<String> tables = resolution.tables();
            List<DictRow> rows = new ArrayList<>();
            Set<String> seenKeys = new LinkedHashSet<>();
            for (String table : tables) {
                rows.addAll(queryDimension(dsId, category, spec, table, seenKeys));
            }
            String note = rows.isEmpty() ? spec.emptyHint() : null;
            log.info("RCAgent 元数据字典直查: category={}, tableSource={}, tableCount={}, hitCount={}",
                    category, resolution.source(), tables.size(), rows.size());
            return new LookupResult(category, List.copyOf(tables), List.copyOf(rows), resolution.source(), note);
        } catch (Exception e) {
            // G-13：失败降级不阻断；异常摘要截断且禁止拼接凭据
            log.warn("RCAgent 元数据字典直查失败，降级不产出证据: category={}, error={}",
                    category, RcaNodeUtils.truncate(String.valueOf(e.getMessage()), 200));
            return LookupResult.empty(category, TABLE_SOURCE_NONE, spec.emptyHint());
        }
    }

    /**
     * 表名解析（包级可见，便于自测）。
     *
     * <p>S1 提取标识符 token（去重保序、上限 10）→ S2 token 作为精确表名经字典验证（问题点名表）→
     * S3 未命中则取最长 token 作单关键词检索（复刻既有 A 路单子串 LIKE 口径，本轮检索命中表）→
     * S4 两皆空 ⇒ {@code source=NONE}（不产出证据）→ S5 命中表按出现顺序取前 {@link #MAX_TABLES} 个。</p>
     *
     * @param dsId     数据源 ID（调用方保证非空白）
     * @param question 用户问题
     * @return 表名解析结果（source ∈ QUESTION_TOKEN / RETRIEVAL_HIT / NONE）
     */
    TableResolution resolveTables(String dsId, String question) {
        List<String> tokens = extractIdentifierTokens(question);
        if (tokens.isEmpty()) {
            return new TableResolution(TABLE_SOURCE_NONE, List.of());
        }
        List<String> named = retrieveTableNames(dsId, null, tokens);
        if (!named.isEmpty()) {
            return new TableResolution(TABLE_SOURCE_QUESTION_TOKEN, limitTables(named));
        }
        String longest = longestToken(tokens);
        List<String> hits = longest == null ? List.of() : retrieveTableNames(dsId, longest, null);
        if (!hits.isEmpty()) {
            return new TableResolution(TABLE_SOURCE_RETRIEVAL_HIT, limitTables(hits));
        }
        return new TableResolution(TABLE_SOURCE_NONE, List.of());
    }

    /**
     * 提取问题中全部标识符 token（去重保序、上限 {@link #IDENTIFIER_TOKEN_LIMIT}）。
     * 包级可见，口径与 {@code RcaRetrievalNode.extractIdentifierTokens} 完全一致。
     */
    static List<String> extractIdentifierTokens(String question) {
        List<String> tokens = new ArrayList<>();
        if (question == null || question.isBlank()) {
            return tokens;
        }
        Matcher matcher = IDENTIFIER_TOKEN.matcher(question);
        Set<String> seen = new HashSet<>();
        while (matcher.find() && tokens.size() < IDENTIFIER_TOKEN_LIMIT) {
            String token = matcher.group();
            if (seen.add(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** 维度规格表（包级可见供自测断言「8 维度全覆盖」） */
    static Map<MetadataCategory, DimSpec> dimensionSpecs() {
        return DIMENSION_SPECS;
    }

    /**
     * 维度直查：每命中表一次 schema 取数（{@code includeIndexes=true, topK=100}），
     * 并按维度在 Java 层做标记过滤；关系维度另调 {@code retrieveRelation}。
     */
    private List<DictRow> queryDimension(String dsId, MetadataCategory category, DimSpec spec,
                                         String table, Set<String> seenKeys) {
        List<DictRow> rows = new ArrayList<>();
        if (spec.needSchemaQuery()) {
            KbSchemaRetrievalVO schema = retrieveTableSchema(dsId, table);
            switch (category) {
                case INDEX -> {
                    for (KbIndexVO index : safeList(schema == null ? null : schema.getIndexes())) {
                        addIndexRow(rows, seenKeys, table, index);
                    }
                }
                case PRIMARY_KEY -> {
                    // 主键索引（is_primary=1）+ 同一次取数结果的 is_pk=1 列佐证（不额外查询）
                    for (KbIndexVO index : safeList(schema == null ? null : schema.getIndexes())) {
                        if (isFlagOn(index.getIsPrimary())) {
                            addIndexRow(rows, seenKeys, table, index);
                        }
                    }
                    for (KbColumnVO column : safeList(schema == null ? null : schema.getColumns())) {
                        if (isFlagOn(column.getIsPk())) {
                            addColumnRow(rows, seenKeys, table, column);
                        }
                    }
                }
                case UNIQUE_KEY -> {
                    for (KbIndexVO index : safeList(schema == null ? null : schema.getIndexes())) {
                        if (isFlagOn(index.getIsUnique())) {
                            addIndexRow(rows, seenKeys, table, index);
                        }
                    }
                }
                case COLUMN_DEF -> {
                    for (KbColumnVO column : safeList(schema == null ? null : schema.getColumns())) {
                        addColumnRow(rows, seenKeys, table, column);
                    }
                }
                case FOREIGN_KEY -> {
                    for (KbColumnVO column : safeList(schema == null ? null : schema.getColumns())) {
                        if (isFlagOn(column.getIsFk())) {
                            addColumnRow(rows, seenKeys, table, column);
                        }
                    }
                }
                case TABLE_DEF -> {
                    for (KbTableVO tableVo : safeList(schema == null ? null : schema.getTables())) {
                        addTableRow(rows, seenKeys, table, tableVo);
                    }
                }
                case CONSTRAINT -> {
                    // kb_column 无「可空/非空」字段（C-05）：尽力返回列清单 + 一行如实说明，禁止编造 NOT NULL
                    for (KbColumnVO column : safeList(schema == null ? null : schema.getColumns())) {
                        addColumnRow(rows, seenKeys, table, column);
                    }
                    if (!rows.isEmpty()) {
                        addNoteRow(rows, seenKeys, table, HINT_CONSTRAINT);
                    }
                }
                case RELATION -> {
                    // 关系维度不依赖 schema 取数（规格 need_schema_query=0），仅走 retrieveRelation
                }
            }
        }
        if (spec.needRelationQuery()) {
            for (KbRelationVO relation : retrieveRelations(dsId, table)) {
                addRelationRow(rows, seenKeys, table, relation);
            }
        }
        return rows;
    }

    /** 单表 schema 取数（按表取数 + topK=100 ⇒ 该表全部索引/列，不被通用 topK 截断） */
    private KbSchemaRetrievalVO retrieveTableSchema(String dsId, String table) {
        KbSchemaRetrievalDTO dto = new KbSchemaRetrievalDTO();
        dto.setDsId(dsId);
        dto.setKeyword(null);
        dto.setTableNames(List.of(table));
        dto.setIncludeDraft(false);
        dto.setIncludeIndexes(true);
        dto.setTopK(TOP_K);
        return kbRetrievalService.retrieveSchema(dto);
    }

    /** 表名验证/回退检索：返回字典中真实存在的表名（去重保序，保持数据库返回顺序） */
    private List<String> retrieveTableNames(String dsId, String keyword, List<String> tableNames) {
        KbSchemaRetrievalDTO dto = new KbSchemaRetrievalDTO();
        dto.setDsId(dsId);
        dto.setKeyword(keyword);
        dto.setTableNames(tableNames == null || tableNames.isEmpty() ? null : tableNames);
        dto.setIncludeDraft(false);
        dto.setIncludeIndexes(false);
        dto.setTopK(TOP_K);
        KbSchemaRetrievalVO vo = kbRetrievalService.retrieveSchema(dto);
        List<String> names = new ArrayList<>();
        if (vo == null || vo.getTables() == null) {
            return names;
        }
        Set<String> seen = new HashSet<>();
        for (KbTableVO table : vo.getTables()) {
            if (table == null || isBlank(table.getTableName())) {
                continue;
            }
            String name = table.getTableName().trim();
            if (seen.add(name.toLowerCase(Locale.ROOT))) {
                names.add(name);
            }
        }
        return names;
    }

    /** 关系取数（仅 RELATION / FOREIGN_KEY 维度）：① table_a 至少一个；② kb_relation 当前为空 ⇒ 返回空列表 */
    private List<KbRelationVO> retrieveRelations(String dsId, String table) {
        KbRelationRetrievalDTO dto = new KbRelationRetrievalDTO();
        dto.setDsId(dsId);
        dto.setTableA(table);
        dto.setTopK(TOP_K);
        KbRelationRetrievalVO vo = kbRetrievalService.retrieveRelation(dto);
        return vo == null ? List.of() : safeList(vo.getRelations());
    }

    /** 索引行（去重键：表名.索引名，小写归一，先到先留） */
    private static void addIndexRow(List<DictRow> rows, Set<String> seenKeys, String table, KbIndexVO index) {
        if (index == null) {
            return;
        }
        String key = dedupeKey(table, index.getIndexName());
        if (!seenKeys.add(key)) {
            return;
        }
        rows.add(new DictRow(table, KIND_INDEX, key, null, index, null, null, null));
    }

    /** 列行（去重键：表名.列名） */
    private static void addColumnRow(List<DictRow> rows, Set<String> seenKeys, String table, KbColumnVO column) {
        if (column == null) {
            return;
        }
        String key = dedupeKey(table, column.getColumnName());
        if (!seenKeys.add(key)) {
            return;
        }
        rows.add(new DictRow(table, KIND_COLUMN, key, null, null, column, null, null));
    }

    /** 表行（去重键：表名） */
    private static void addTableRow(List<DictRow> rows, Set<String> seenKeys, String table, KbTableVO tableVo) {
        if (tableVo == null) {
            return;
        }
        String key = dedupeKey(table, null);
        if (!seenKeys.add(key)) {
            return;
        }
        String name = isBlank(tableVo.getTableName()) ? table : tableVo.getTableName();
        rows.add(new DictRow(name, KIND_TABLE, key, null, null, null, tableVo, null));
    }

    /** 关系行（去重键：表A.列A=表B.列B） */
    private static void addRelationRow(List<DictRow> rows, Set<String> seenKeys, String table, KbRelationVO relation) {
        if (relation == null) {
            return;
        }
        String key = (safe(relation.getTableA()) + "." + safe(relation.getColumnA())
                + "=" + safe(relation.getTableB()) + "." + safe(relation.getColumnB())).toLowerCase(Locale.ROOT);
        if (!seenKeys.add(key)) {
            return;
        }
        String name = isBlank(relation.getTableA()) ? table : relation.getTableA();
        rows.add(new DictRow(name, KIND_RELATION, key, null, null, null, null, relation));
    }

    /** 如实说明行（去重键：表名.constraint） */
    private static void addNoteRow(List<DictRow> rows, Set<String> seenKeys, String table, String note) {
        String key = dedupeKey(table, "constraint");
        if (!seenKeys.add(key)) {
            return;
        }
        rows.add(new DictRow(table, KIND_NOTE, key, note, null, null, null, null));
    }

    /** 去重键：{@code 表名.限定名}，小写归一（限定名为 null 时退化为 表名） */
    private static String dedupeKey(String table, String qualifier) {
        String key = qualifier == null || qualifier.isBlank() ? safe(table) : safe(table) + "." + qualifier;
        return key.toLowerCase(Locale.ROOT);
    }

    /** 命中表按出现顺序裁剪到 {@link #MAX_TABLES} 个 */
    private static List<String> limitTables(List<String> tables) {
        if (tables.size() <= MAX_TABLES) {
            return List.copyOf(tables);
        }
        return List.copyOf(tables.subList(0, MAX_TABLES));
    }

    /** 最长 token（复刻既有「单关键字 LIKE」选词口径；同长度取先出现者） */
    private static String longestToken(List<String> tokens) {
        String longest = null;
        for (String token : tokens) {
            if (longest == null || token.length() > longest.length()) {
                longest = token;
            }
        }
        return longest;
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** 0/1 标记判定：null 视同 0（与 RetrievalAdapter.isFlagOn 口径一致） */
    private static boolean isFlagOn(Integer flag) {
        return flag != null && flag == 1;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 构建 8 维度规格映射（文案与渲染层兜底文案同源，避免两处漂移） */
    private static Map<MetadataCategory, DimSpec> buildDimensionSpecs() {
        EnumMap<MetadataCategory, DimSpec> specs = new EnumMap<>(MetadataCategory.class);
        specs.put(MetadataCategory.INDEX, new DimSpec("kb_index", "ALL", KIND_INDEX,
                true, false, true, RcaMetadataEvidenceRenderer.emptyHint(MetadataCategory.INDEX)));
        specs.put(MetadataCategory.PRIMARY_KEY, new DimSpec("kb_index", "is_primary=1", KIND_INDEX,
                true, false, true, RcaMetadataEvidenceRenderer.emptyHint(MetadataCategory.PRIMARY_KEY)));
        specs.put(MetadataCategory.UNIQUE_KEY, new DimSpec("kb_index", "is_unique=1", KIND_INDEX,
                true, false, true, RcaMetadataEvidenceRenderer.emptyHint(MetadataCategory.UNIQUE_KEY)));
        specs.put(MetadataCategory.FOREIGN_KEY, new DimSpec("kb_column", "is_fk=1", KIND_COLUMN,
                true, true, true, RcaMetadataEvidenceRenderer.emptyHint(MetadataCategory.FOREIGN_KEY)));
        specs.put(MetadataCategory.COLUMN_DEF, new DimSpec("kb_column", "ALL", KIND_COLUMN,
                true, false, true, RcaMetadataEvidenceRenderer.emptyHint(MetadataCategory.COLUMN_DEF)));
        specs.put(MetadataCategory.TABLE_DEF, new DimSpec("kb_table", "ALL", KIND_TABLE,
                true, false, true, RcaMetadataEvidenceRenderer.emptyHint(MetadataCategory.TABLE_DEF)));
        specs.put(MetadataCategory.CONSTRAINT, new DimSpec("kb_column", "ALL", KIND_NOTE,
                true, false, true, RcaMetadataEvidenceRenderer.emptyHint(MetadataCategory.CONSTRAINT)));
        specs.put(MetadataCategory.RELATION, new DimSpec("kb_relation", "ALL", KIND_RELATION,
                false, true, true, RcaMetadataEvidenceRenderer.emptyHint(MetadataCategory.RELATION)));
        return Collections.unmodifiableMap(specs);
    }

    /**
     * 直查结果（不可变读模型，对应逻辑模型 {@code t_dict_row} 容器）。
     *
     * @param category       本次直查维度
     * @param resolvedTables 命中的表名清单（上限 {@link #MAX_TABLES}；空列表 ⇒ 上游不产出证据段）
     * @param rows           证据行（保持数据库返回顺序，不做二次排序）
     * @param tableSource    表名来源（QUESTION_TOKEN / RETRIEVAL_HIT / NONE）
     * @param note           维度级如实说明（可空）
     */
    public record LookupResult(MetadataCategory category, List<String> resolvedTables, List<DictRow> rows,
                               String tableSource, String note) {

        /** 空结果工厂（降级不产出证据） */
        public static LookupResult empty(MetadataCategory category, String tableSource, String reason) {
            return new LookupResult(category, List.of(), List.of(), tableSource, reason);
        }
    }

    /**
     * 直查结果行（不可变，对应逻辑模型 {@code t_dict_row} 一行）。
     *
     * @param tableName 该行所属表名（证据行展示与去重键）
     * @param kind      行类型（INDEX / COLUMN / TABLE / RELATION / NOTE）
     * @param dedupeKey 去重键（小写归一）
     * @param note      如实说明文本（kind=NOTE 时有值）
     * @param index     kind=INDEX 时的行实体（只读引用）
     * @param column    kind=COLUMN 时的行实体
     * @param table     kind=TABLE 时的行实体
     * @param relation  kind=RELATION 时的行实体
     */
    public record DictRow(String tableName, String kind, String dedupeKey, String note,
                          KbIndexVO index, KbColumnVO column, KbTableVO table, KbRelationVO relation) {
    }

    /**
     * 表名解析结果（不可变，对应逻辑模型 {@code t_metadata_table_candidate}）。
     *
     * @param source 来源（QUESTION_TOKEN / RETRIEVAL_HIT / NONE）
     * @param tables 解析出的表名（保持问题出现顺序，上限 {@link #MAX_TABLES}）
     */
    public record TableResolution(String source, List<String> tables) {
    }

    /**
     * 维度直查规格（对应逻辑模型 {@code t_metadata_dim_query} 一行，代码常量承载、不落库）。
     *
     * @param dictTable         字典来源表（kb_index / kb_column / kb_table / kb_relation）
     * @param dimFilter         Java 层过滤表达式（ALL / is_primary=1 / is_unique=1 / is_pk=1 / is_fk=1）
     * @param rowKind           证据行类型
     * @param needSchemaQuery   是否需要 retrieveSchema(includeIndexes=true) 取数
     * @param needRelationQuery 是否需要 retrieveRelation 取数
     * @param requiresTableName 无表名时是否降级不产出（本模块恒为 true）
     * @param emptyHint         维度无行时的如实说明文案
     */
    record DimSpec(String dictTable, String dimFilter, String rowKind, boolean needSchemaQuery,
                   boolean needRelationQuery, boolean requiresTableName, String emptyHint) {
    }
}
