package com.agenthub.ai.rcagent.engine.support;

import com.agenthub.ai.rcagent.engine.support.RcaMetadataDictionaryLookup.DictRow;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataDictionaryLookup.LookupResult;
import com.agenthub.ai.rcagent.engine.support.RcaMetadataQuestionDetector.MetadataCategory;
import com.agenthub.ai.rcagent.kb.common.KbJsonUtils;
import com.agenthub.ai.rcagent.kb.vo.KbColumnVO;
import com.agenthub.ai.rcagent.kb.vo.KbIndexVO;
import com.agenthub.ai.rcagent.kb.vo.KbRelationVO;
import com.agenthub.ai.rcagent.kb.vo.KbTableVO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * RCAgent 元数据证据段渲染器（rca1.5.0.0 metadata-evidence-lookup）。
 *
 * <p>职责：把 {@link LookupResult} 渲染为 {@code [元数据字典直查]} 证据段文本（纯函数、零依赖、静态方法）。</p>
 *
 * <p>渲染契约（与 rca1.3.0.0 定稿逐字符一致，不得改动既有两行）：</p>
 * <ul>
 *   <li>索引行 = {@code 索引:{indexName} 列:{c1,c2} 唯一:{是|否} 主键:{是|否}[ 类型:{indexType}]}
 *       （空列清单 ⇒ {@code 列:-}；indexType 空白不输出类型段）——复刻
 *       {@code RetrievalAdapter.buildIndexContent}（原方法 private，存量类零改动，故逐字符复刻）；</li>
 *   <li>列行 = {@code {commentText} 类型:{dataType} [主键] [外键]}（isPk/isFk 为 1 才追加，主键在前，null 视同 0）
 *       ——复刻 {@code RetrievalAdapter.buildColumnContent}；</li>
 *   <li>表行 / 关系行 / 说明行为本期新增，均带 {@code - [{tableName}] } 前缀；
 *       前缀不破坏 {@code 索引:...} 契约子串（rc-answer 仍可识别）。</li>
 * </ul>
 *
 * <p>段长护栏：{@code SECTION_MAX = 2000}，超限按行截断并保留段头与段尾（G-08/G-12）。</p>
 *
 * <p>安全：段内容全部来自已验证字典（列注释/类型/索引定义等元数据），不含凭据类字段。</p>
 */
public final class RcaMetadataEvidenceRenderer {

    /** 段头来源标注（要求 4：显式标注证据来源） */
    private static final String SECTION_HEAD_PREFIX = "[元数据字典直查] 维度:";
    /** 段尾固定标注 */
    static final String SECTION_TAIL = "[元数据字典直查-结束]";
    /** 证据行统一前缀：{@code - [表名] } */
    private static final String ROW_PREFIX_LEFT = "- [";
    private static final String ROW_PREFIX_RIGHT = "] ";
    /** 段长上限：超出按行截断（段头/段尾优先保留） */
    static final int SECTION_MAX = 2000;
    /** 行分隔符 */
    private static final String LINE_SEPARATOR = "\n";

    /**
     * 维度级空结果如实说明（{@link LookupResult#note()} 缺失时的兜底文案）。
     *
     * <p>主来源为 {@link RcaMetadataDictionaryLookup} 的维度规格（同一份文案），此处仅兜底，
     * 保证「无行时必有如实说明」而非空白（FR-BE-013）。</p>
     */
    private static final Map<MetadataCategory, String> EMPTY_HINTS = buildEmptyHints();

    private RcaMetadataEvidenceRenderer() {
    }

    /**
     * 渲染证据段。
     *
     * @param result 直查结果（可为 null）
     * @return 段文本；{@code result} 为 null 或 {@code resolvedTables} 为空 ⇒ 返回空串（上游据此不追加证据）
     */
    public static String render(LookupResult result) {
        if (result == null || result.resolvedTables() == null || result.resolvedTables().isEmpty()) {
            return "";
        }
        List<String> lines = buildRowLines(result);
        if (lines.isEmpty()) {
            lines = List.of(buildEmptyMarker(result));
        }
        String head = buildHead(result);
        StringBuilder sb = new StringBuilder(head);
        // 预算中先扣除段尾与换行，保证「按行截断 + 段头段尾保留」后段长不超上限
        int used = head.length() + LINE_SEPARATOR.length() + SECTION_TAIL.length();
        for (String line : lines) {
            int cost = line.length() + LINE_SEPARATOR.length();
            if (used + cost > SECTION_MAX) {
                break;
            }
            sb.append(LINE_SEPARATOR).append(line);
            used += cost;
        }
        sb.append(LINE_SEPARATOR).append(SECTION_TAIL);
        return sb.toString();
    }

    /** 段头：{@code [元数据字典直查] 维度:{category}，范围表:{t1,t2}，共 {n} 条} */
    private static String buildHead(LookupResult result) {
        List<String> tables = result.resolvedTables() == null ? List.of() : result.resolvedTables();
        int hitCount = result.rows() == null ? 0 : result.rows().size();
        return SECTION_HEAD_PREFIX + result.category()
                + "，范围表:" + String.join(",", tables)
                + "，共 " + hitCount + " 条";
    }

    /** 逐行渲染证据行（无法渲染的行直接跳过，不影响其余行） */
    private static List<String> buildRowLines(LookupResult result) {
        List<String> lines = new ArrayList<>();
        if (result.rows() == null) {
            return lines;
        }
        for (DictRow row : result.rows()) {
            if (row == null) {
                continue;
            }
            String content = renderRowContent(row);
            if (content == null || content.isEmpty()) {
                continue;
            }
            lines.add(ROW_PREFIX_LEFT + row.tableName() + ROW_PREFIX_RIGHT + content);
        }
        return lines;
    }

    /** 按行类型渲染行内容（kind 未知或实体缺失 ⇒ 返回 null，行被跳过） */
    private static String renderRowContent(DictRow row) {
        String kind = row.kind();
        if (RcaMetadataDictionaryLookup.KIND_INDEX.equals(kind)) {
            return row.index() == null ? null : indexLine(row.index());
        }
        if (RcaMetadataDictionaryLookup.KIND_COLUMN.equals(kind)) {
            return row.column() == null ? null : columnLine(row.column());
        }
        if (RcaMetadataDictionaryLookup.KIND_TABLE.equals(kind)) {
            return row.table() == null ? null : tableLine(row.table());
        }
        if (RcaMetadataDictionaryLookup.KIND_RELATION.equals(kind)) {
            return row.relation() == null ? null : relationLine(row.relation());
        }
        if (RcaMetadataDictionaryLookup.KIND_NOTE.equals(kind)) {
            return row.note() == null || row.note().isBlank() ? null : "说明:" + row.note();
        }
        return null;
    }

    /**
     * 无证据行时的如实说明行（顶层行，无表名前缀）：优先用 {@link LookupResult#note()}
     * （如 {@code 字典未收录该表索引}），缺失时回退维度级兜底文案；严禁编造（FR-BE-013）。
     */
    private static String buildEmptyMarker(LookupResult result) {
        String note = result.note();
        if (note != null && !note.isBlank()) {
            return "- 说明:" + note;
        }
        String hint = emptyHint(result.category());
        return hint == null
                ? "- （字典未收录该维度信息）"
                : "- （字典未收录该维度信息：" + hint + "）";
    }

    /**
     * 维度级空结果文案（与 data-model §1 {@code empty_hint} 一致；包级可见供直查组件与自测复用）。
     *
     * @param category 维度（可为 null）
     * @return 文案；未配置到位时返回 null
     */
    static String emptyHint(MetadataCategory category) {
        return category == null ? null : EMPTY_HINTS.get(category);
    }

    /**
     * 索引行渲染（逐字符契约，复刻 {@code RetrievalAdapter.buildIndexContent}）：
     * {@code 索引:{indexName} 列:{c1,c2} 唯一:{是|否} 主键:{是|否}[ 类型:{indexType}]}。
     */
    static String indexLine(KbIndexVO index) {
        if (index == null) {
            return null;
        }
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

    /**
     * 列行渲染（逐字符契约，复刻 {@code RetrievalAdapter.buildColumnContent}）：
     * {@code {commentText} 类型:{dataType} [主键] [外键]}；null 视同 0，主键在前。
     */
    static String columnLine(KbColumnVO column) {
        if (column == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (column.getCommentText() != null) {
            sb.append(column.getCommentText());
        }
        if (column.getDataType() != null) {
            sb.append(" 类型:").append(column.getDataType());
        }
        if (isFlagOn(column.getIsPk())) {
            sb.append(" [主键]");
        }
        if (isFlagOn(column.getIsFk())) {
            sb.append(" [外键]");
        }
        return sb.toString();
    }

    /** 表行渲染（本期新增）：{@code 表:{tableName} 类型:{tableType} 注释:{commentText}}，缺失字段不输出对应段 */
    static String tableLine(KbTableVO table) {
        if (table == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("表:").append(safe(table.getTableName()));
        if (table.getTableType() != null && !table.getTableType().isBlank()) {
            sb.append(" 类型:").append(table.getTableType());
        }
        if (table.getCommentText() != null && !table.getCommentText().isBlank()) {
            sb.append(" 注释:").append(table.getCommentText());
        }
        return sb.toString();
    }

    /**
     * 关系行渲染（本期新增）：{@code 关系:{tableA}.{columnA}={tableB}.{columnB} 类型:{relationType}}。
     * 四个定位字段不完整时返回 null（不输出残缺关系行）。
     */
    static String relationLine(KbRelationVO relation) {
        if (relation == null
                || isBlank(relation.getTableA()) || isBlank(relation.getColumnA())
                || isBlank(relation.getTableB()) || isBlank(relation.getColumnB())) {
            return null;
        }
        StringBuilder sb = new StringBuilder("关系:")
                .append(relation.getTableA()).append(".").append(relation.getColumnA())
                .append("=").append(relation.getTableB()).append(".").append(relation.getColumnB());
        if (relation.getRelationType() != null && !relation.getRelationType().isBlank()) {
            sb.append(" 类型:").append(relation.getRelationType());
        }
        return sb.toString();
    }

    /** 索引列清单 JSON 文本 → 列清单；null/空白/非法均降级为空列表（渲染为 {@code 列:-}），不影响其余行 */
    private static List<String> parseIndexColumns(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> columns = KbJsonUtils.parseStringList(json);
            return columns == null ? List.of() : columns;
        } catch (Exception e) {
            // 非法 JSON 只影响本条索引行渲染（降级为 列:-），与 RetrievalAdapter.parseIndexColumns 同口径
            return List.of();
        }
    }

    /** 0/1 标记判定：null 视同 0（与 RetrievalAdapter.isFlagOn 完全一致） */
    private static boolean isFlagOn(Integer flag) {
        return flag != null && flag == 1;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static Map<MetadataCategory, String> buildEmptyHints() {
        EnumMap<MetadataCategory, String> hints = new EnumMap<>(MetadataCategory.class);
        hints.put(MetadataCategory.INDEX, "字典未收录该表索引");
        hints.put(MetadataCategory.PRIMARY_KEY, "字典未收录该表主键索引");
        hints.put(MetadataCategory.FOREIGN_KEY, "字典未收录该表外键列");
        hints.put(MetadataCategory.UNIQUE_KEY, "字典未收录该表唯一索引");
        hints.put(MetadataCategory.TABLE_DEF, "字典未收录该表");
        hints.put(MetadataCategory.COLUMN_DEF, "字典未收录该表字段");
        hints.put(MetadataCategory.CONSTRAINT, "元数据字典未收录非空约束明细（尽力返回列清单）");
        hints.put(MetadataCategory.RELATION, "kb_relation 字典当前未收录表关系");
        return Collections.unmodifiableMap(hints);
    }
}
