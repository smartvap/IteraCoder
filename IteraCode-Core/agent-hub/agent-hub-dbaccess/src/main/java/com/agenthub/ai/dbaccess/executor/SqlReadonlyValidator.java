package com.agenthub.ai.dbaccess.executor;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读 SQL 文本校验/轻量解析（非完整 SQL 解析器）。
 *
 * <p>职责：去注释/去尾分号、首关键字只读校验、SELECT * 探测、引用表名/投影列提取、标识符命中探测。
 * 设计取舍：P0 采用保守启发式解析；无法可靠解析且涉及列级白名单时按 fail-closed 拒绝（注释于调用方）。</p>
 */
@Component
public class SqlReadonlyValidator {

    private static final Pattern REFERENCED_TABLE = Pattern.compile(
            "(?i)\\b(?:from|join)\\s+([A-Za-z0-9_$`\"\\[\\]]+(?:\\s*\\.\\s*[A-Za-z0-9_$`\"\\[\\]]+)?)");
    private static final Pattern PROJECTION_STAR = Pattern.compile(
            "(?i)^\\s*(?:distinct\\s+|all\\s+)?(?:[\\w$`\"\\[\\]]+\\.)?\\*\\s*$");
    /** 派生表/子查询探测（快速路径）：FROM/JOIN 后紧跟左括号（外层裸列来源不可信） */
    private static final Pattern DERIVED_TABLE = Pattern.compile("(?i)\\b(?:from|join)\\s*\\(");

    /** FROM/JOIN 子句结束关键字：一旦在最外层表源列表遇到即停止派生表扫描（其后括号不再属于表源列表） */
    private static final Set<String> FROM_CLAUSE_BREAKERS = Set.of(
            "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "UNION", "OFFSET", "FETCH", "FOR", "INTO");

    /**
     * 校验用文本：去除注释、去除尾部多余分号后 trim。
     */
    public String forValidation(String sql) {
        if (sql == null) {
            return "";
        }
        String noComments = stripComments(sql);
        return stripTrailingSemicolon(noComments).trim();
    }

    /**
     * 执行用文本：仅移除尾部分号（不破坏字符串字面量），保留注释语义交由数据库处理。
     */
    public String forExecution(String sql) {
        if (sql == null) {
            return "";
        }
        return stripTrailingSemicolon(sql).trim();
    }

    /**
     * 首个关键字（大写），空返回空串。
     */
    public String firstKeyword(String validationText) {
        String text = validationText.trim();
        if (text.isEmpty()) {
            return "";
        }
        int end = 0;
        while (end < text.length() && (Character.isLetter(text.charAt(end)) || text.charAt(end) == '_')) {
            end++;
        }
        return text.substring(0, end).toUpperCase(Locale.ROOT);
    }

    /**
     * 是否存在顶层 SELECT * 投影（count(*) 等函数内 * 不命中）。
     */
    public boolean hasTopLevelSelectStar(String validationText) {
        String projection = projection(validationText);
        if (projection == null || projection.isBlank()) {
            return false;
        }
        for (String item : splitTopLevelItems(projection)) {
            if (item != null && PROJECTION_STAR.matcher(item).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 顶层投影列（启发式：仅识别非函数裸列/别名列；含函数/无法解析时返回空集合）。
     *
     * <p>派生表/子查询场景下外层裸列来源不可信（可能为内层别名重命名），
     * 返回空集合触发调用方列级白名单 fail-closed 拒绝，避免基于错误解析放行（问题1）。</p>
     */
    public List<String> projectionColumns(String validationText) {
        String projection = projection(validationText);
        if (projection == null || projection.isBlank()) {
            return new ArrayList<>();
        }
        if (hasDerivedTable(validationText)) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String item : splitTopLevelItems(projection)) {
            String col = parseProjectionItem(item);
            if (col != null) {
                result.add(col);
            }
        }
        return result;
    }

    /**
     * 按 select 列表项顺序解析投影：每项返回其来源列（裸列）或 null。
     *
     * <p>与 {@link #projectionColumns} 差异：不跳过函数/无法解析项，顺序与结果集列一一对应，
     * 供敏感判定做「结果列标签 → 真实投影列」映射（封堵 SELECT phone AS p 别名绕过）。</p>
     *
     * <p>可信度规则：当 SQL 含派生表/子查询（FROM/JOIN 后括号）时，外层裸列引用可能只是子查询
     * 内层重命名后的别名（如 {@code SELECT p FROM (SELECT phone AS p FROM customer) t}），
     * 无法确认真实来源，统一按 null（来源不可信）返回，交由执行器保守兜底，禁止明文回退。</p>
     */
    public List<String> projectionColumnsByPosition(String validationText) {
        String projection = projection(validationText);
        if (projection == null || projection.isBlank()) {
            return new ArrayList<>();
        }
        boolean derivedTable = hasDerivedTable(validationText);
        List<String> result = new ArrayList<>();
        for (String item : splitTopLevelItems(projection)) {
            String col = parseProjectionSourceColumn(item);
            result.add(derivedTable && col != null ? null : col);
        }
        return result;
    }

    /**
     * 是否存在派生表/子查询（FROM/JOIN 子句中出现括号子查询表源）。
     *
     * <p>快速路径：FROM/JOIN 后紧跟左括号（{@code FROM (SELECT ...)} / {@code JOIN (SELECT ...)}）。
     * 补充路径：逗号连接多表派生表（{@code FROM a, (SELECT ...) x}、{@code FROM a JOIN b, (SELECT ...) x}）
     * 与 UNION 后续 SELECT 的派生表来源 —— 对每个顶层 FROM 的表源列表扫描，出现「左括号后紧跟
     * SELECT/WITH」即视为外层投影裸列来源不可信（可能是内层别名重命名，禁止按真实列放行）。
     * WHERE 子句内的 {@code IN (SELECT ...)} 不属于表源，不在本探测范围（表源列表在遇到
     * WHERE/GROUP/ORDER 等终止关键字后结束）。字符串字面量内出现 "from (" 极罕见且本身有歧义，
     * 此处宁可保守判为派生表。</p>
     */
    public boolean hasDerivedTable(String validationText) {
        if (validationText == null || validationText.isBlank()) {
            return false;
        }
        if (DERIVED_TABLE.matcher(validationText).find()) {
            return true;
        }
        return scanTopLevelFromClausesForDerivedTable(validationText);
    }

    /**
     * 遍历每个顶层 FROM 关键字（跳过子查询内/字符串/注释，UNION 后多个顶层 SELECT 各自覆盖），
     * 若任一表源列表出现括号子查询表源则返回 true。
     */
    private boolean scanTopLevelFromClausesForDerivedTable(String text) {
        int from = 0;
        while (true) {
            int fromIdx = indexOfTopLevelKeyword(text, "from", from);
            if (fromIdx < 0) {
                return false;
            }
            if (fromClauseHasParenthesizedSource(text, fromIdx + "from".length())) {
                return true;
            }
            from = fromIdx + "from".length();
        }
    }

    /**
     * 扫描单个顶层 FROM 之后的表源列表，直到字符串结束或遇到顶层终止关键字。
     * 在顶层（depth=0）遇到 '(' 且其后（跳过空白）紧跟 SELECT/WITH → 括号子查询派生表，返回 true。
     */
    private boolean fromClauseHasParenthesizedSource(String text, int start) {
        int n = text.length();
        int i = start;
        int depth = 0;
        char quote = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < n && text.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                i++;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                i++;
                continue;
            }
            if (c == '(') {
                if (depth == 0 && isSelectLikeAt(text, i + 1)) {
                    return true;
                }
                depth++;
                i++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                i++;
                continue;
            }
            if (depth == 0 && (Character.isLetter(c) || c == '_')) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                    j++;
                }
                String word = text.substring(i, j);
                if (FROM_CLAUSE_BREAKERS.contains(word.toUpperCase(Locale.ROOT))) {
                    return false;
                }
                i = j;
                continue;
            }
            i++;
        }
        return false;
    }

    /** 位置 pos 起（跳过空白）是否紧跟 SELECT/WITH 关键字（括号子查询起始）。 */
    private boolean isSelectLikeAt(String text, int pos) {
        int n = text.length();
        int i = pos;
        while (i < n && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i >= n) {
            return false;
        }
        String head = text.substring(i);
        if (head.regionMatches(true, 0, "select", 0, "select".length())) {
            return isKeywordBoundary(head, "select".length());
        }
        if (head.regionMatches(true, 0, "with", 0, "with".length())) {
            return isKeywordBoundary(head, "with".length());
        }
        return false;
    }

    private boolean isKeywordBoundary(String text, int keywordLen) {
        return text.length() == keywordLen
                || !(Character.isLetterOrDigit(text.charAt(keywordLen)) || text.charAt(keywordLen) == '_');
    }

    /**
     * 解析单个投影项的真实来源列（不剥离别名；仅裸列/带表前缀列可解析，其余返回 null）。
     */
    private String parseProjectionSourceColumn(String item) {
        if (item == null) {
            return null;
        }
        String text = item.trim();
        if (text.isEmpty() || text.indexOf('(') >= 0) {
            return null;
        }
        text = text.replaceFirst("(?i)^(distinct|all)\\s+", "").trim();
        if (text.equals("*") || text.endsWith(".*")) {
            return null;
        }
        // 剥离 AS 别名/隐式别名（首个空白段为来源表达式）
        String head = text.split("\\s+")[0].trim();
        if (head.isEmpty() || head.indexOf('(') >= 0) {
            return null;
        }
        int dot = head.lastIndexOf('.');
        String col = dot >= 0 ? head.substring(dot + 1) : head;
        // 统一复用 WhitelistRule.normalizeTable（剥离 `"[]三种引号 + 大写），避免口径分叉
        String normalized = normalizeIdentifier(col);
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * 引用表集合（from/join 之后标识符；子查询中的表一并捕获；别名/库名前缀剥离）。
     */
    public Set<String> referencedTables(String validationText) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = REFERENCED_TABLE.matcher(validationText);
        while (matcher.find()) {
            String capture = matcher.group(1).trim();
            String[] parts = capture.split("\\s*\\.\\s*");
            String table = parts[parts.length - 1].trim();
            tables.add(com.agenthub.ai.dbaccess.model.WhitelistRule.normalizeTable(table));
        }
        return tables;
    }

    /**
     * 引用表的「库.表」限定名集合（保留原始 schema 前缀，供跨库边界校验）。
     *
     * <p>与 {@link #referencedTables} 同源遍历 FROM/JOIN 后的标识符，但保留库前缀：
     * capture 含点号时返回 {@code SCHEMA.TABLE}（两段各自 normalizeTable），无点号时返回纯表名。
     * 子查询中的表一并捕获；别名（如 {@code DB_A.T1 a}）不进入捕获；反引号/双引号/方括号形态
     * （如 {@code `DB_A`.`T1`}、{@code [DB_A].[T1]}）经 normalizeTable 剥离后得到 {@code DB_A.T1}。</p>
     *
     * <p>返回语义与 {@link #referencedTables}（纯表名，供表白名单/敏感预检）相互独立，后者不做改动，
     * 避免白名单/敏感字段预检回归。</p>
     */
    public Set<String> referencedQualifiedTables(String validationText) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = REFERENCED_TABLE.matcher(validationText);
        while (matcher.find()) {
            String capture = matcher.group(1).trim();
            String[] parts = capture.split("\\s*\\.\\s*");
            if (parts.length >= 2) {
                String schema = normalizeIdentifier(parts[parts.length - 2]);
                String table = normalizeIdentifier(parts[parts.length - 1]);
                if (!schema.isEmpty() && !table.isEmpty()) {
                    tables.add(schema + "." + table);
                    continue;
                }
            }
            String table = normalizeIdentifier(parts[parts.length - 1]);
            if (!table.isEmpty()) {
                tables.add(table);
            }
        }
        return tables;
    }

    /**
     * 从「库.表」限定名解析 schema（库）前缀；无前缀/非法（空段）返回 null。
     *
     * <p>调用方据此判定是否做了跨库引用：返回非空表示 SQL 显式指定了库（需做库集合边界校验），
     * 返回 null 表示引用的是连接默认库（catalog/spaceName），不参与跨库边界拦截。</p>
     */
    public static String schemaOf(String qualifiedTable) {
        if (qualifiedTable == null) {
            return null;
        }
        int dot = qualifiedTable.indexOf('.');
        if (dot <= 0 || dot >= qualifiedTable.length() - 1) {
            return null;
        }
        return qualifiedTable.substring(0, dot);
    }

    /** 单段标识符归一化（复用 WhitelistRule 口径：剥离反引号/双引号/方括号并转大写）。 */
    private String normalizeIdentifier(String segment) {
        return com.agenthub.ai.dbaccess.model.WhitelistRule.normalizeTable(segment);
    }

    /**
     * 文本是否以独立词形式包含标识符（用于敏感字段 DENY 预检；保守匹配，宁可多拒）。
     */
    public boolean containsIdentifier(String validationText, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return false;
        }
        String word = Pattern.quote(identifier.trim());
        return Pattern.compile("(?i)(?<![A-Za-z0-9_$])" + word + "(?![A-Za-z0-9_$])")
                .matcher(validationText).find();
    }

    /**
     * 提取 select 与顶层 from 之间的投影子串（含 distinct/all）。
     */
    private String projection(String validationText) {
        String text = validationText.trim();
        int selectIdx = indexOfTopLevelKeyword(text, "select", 0);
        if (selectIdx < 0) {
            return null;
        }
        int selEnd = selectIdx + "select".length();
        int fromIdx = indexOfTopLevelKeyword(text, "from", selEnd);
        if (fromIdx < 0) {
            return text.substring(selEnd);
        }
        return text.substring(selEnd, fromIdx);
    }

    /**
     * 解析单个投影项为列名（仅裸列/别名列；函数与 * 返回 null）。
     */
    private String parseProjectionItem(String item) {
        if (item == null) {
            return null;
        }
        String text = item.trim();
        if (text.isEmpty() || text.indexOf('(') >= 0) {
            return null;
        }
        text = text.replaceFirst("(?i)^(distinct|all)\\s+", "").trim();
        if (text.equals("*") || text.endsWith(".*")) {
            return null;
        }
        // 剥离 as 别名/隐式别名（首个空白段）
        String head = text.split("\\s+")[0].trim();
        int dot = head.lastIndexOf('.');
        String col = dot >= 0 ? head.substring(dot + 1) : head;
        // 统一复用 WhitelistRule.normalizeTable（剥离 `"[]三种引号 + 大写），避免口径分叉
        String normalized = normalizeIdentifier(col);
        return normalized.isEmpty() ? null : normalized;
    }

    private List<String> splitTopLevelItems(String projection) {
        List<String> items = new ArrayList<>();
        int depth = 0;
        char quote = 0;
        int start = 0;
        int n = projection.length();
        for (int i = 0; i < n; i++) {
            char c = projection.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < n && projection.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (c == ',' && depth == 0) {
                items.add(projection.substring(start, i));
                start = i + 1;
            }
        }
        items.add(projection.substring(start));
        return items;
    }

    private String stripComments(String sql) {
        StringBuilder sb = new StringBuilder(sql.length());
        int n = sql.length();
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (blockComment) {
                if (c == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    sb.append(' ');
                }
                continue;
            }
            if (quote != 0) {
                sb.append(c);
                if (c == quote) {
                    if (i + 1 < n && sql.charAt(i + 1) == quote) {
                        sb.append(sql.charAt(i + 1));
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                sb.append(c);
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                blockComment = true;
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private String stripTrailingSemicolon(String sql) {
        String text = sql;
        while (!text.isEmpty() && text.charAt(text.length() - 1) == ';') {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 顶层关键字查找（跳过字符串/反引号/注释/括号内）。
     */
    private int indexOfTopLevelKeyword(String text, String keyword, int from) {
        int depth = 0;
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        int n = text.length();
        for (int i = from; i < n; i++) {
            char c = text.charAt(i);
            if (blockComment) {
                if (c == '*' && i + 1 < n && text.charAt(i + 1) == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (quote != 0) {
                if (c == quote) {
                    if (i + 1 < n && text.charAt(i + 1) == quote) {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                continue;
            }
            if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0 && (Character.isLetter(c) || c == '_')) {
                int j = i;
                while (j < n && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                    j++;
                }
                String word = text.substring(i, j);
                if (word.equalsIgnoreCase(keyword)) {
                    return i;
                }
                i = j - 1;
            }
        }
        return -1;
    }
}
