package com.agenthub.ai.rcagent.engine.support;

import com.agenthub.ai.rcagent.sqlguard.model.SqlPlanView;
import com.agenthub.ai.rcagent.sqlguard.model.TimeRange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RcaSqlPlanNode Markdown 规划文本 → {@link SqlPlanView} 只读视图解析器（P2 L3 语义兜底）。
 *
 * <p>背景：RcaValidateNode 此前未把 sql_plan 传入 {@code SqlGuardRequest.plan}，L3 语义对照恒被
 * SKIP，导致"规划明确写了筛选条件、生成 SQL 却无 WHERE"这类明显不一致无任何环节发现。本解析器
 * 把规划文本还原为结构化视图，供 {@code L3SemanticChecker} 对照。</p>
 *
 * <p>约束：纯函数、无副作用、健壮——不修改入参、不抛异常；文本为空或未提取到任何可对照信息时
 * 返回 {@code null}，以保持 L3 SKIP 旧行为（避免无依据地放宽/收紧校验）。</p>
 */
public final class RcaPlanViewParser {

    /** 章节标签（与 RcaSqlPlanNode 提示词输出格式一致；兼容"候选主表/目标主表"两种写法） */
    private static final String LABEL_QUERY_GOAL = "查询目标";
    private static final String LABEL_CANDIDATE_TABLE = "候选主表";
    private static final String LABEL_TARGET_TABLE = "目标主表";
    private static final String LABEL_TIME_RANGE = "时间范围";
    private static final String LABEL_DIMENSION = "分组维度";
    private static final String LABEL_FILTER = "筛选条件";
    private static final String LABEL_AGGREGATE = "聚合方式";
    private static final String LABEL_RISK = "风险提示";

    private static final List<String> LABELS = List.of(
            LABEL_QUERY_GOAL, LABEL_CANDIDATE_TABLE, LABEL_TARGET_TABLE,
            LABEL_TIME_RANGE, LABEL_DIMENSION, LABEL_FILTER, LABEL_AGGREGATE, LABEL_RISK);

    /** SQL 标识符形态（表名/列名） */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /** 日期 token（2024-01-01 / 2024/1/1 / 2024年1月1日） */
    private static final Pattern DATE_TOKEN =
            Pattern.compile("\\d{4}\\s*[-/年]\\s*\\d{1,2}(?:\\s*[-/月]\\s*\\d{1,2}\\s*日?)?");

    /** "无"语义整值匹配（无/无需/无筛选条件/无（...）/暂无/未指定/不限 等，含尾部括号补充说明） */
    private static final Pattern NONE_VALUE = Pattern.compile(
            "^(?:无(?:需|筛选条件|筛选|分组|指定)?|暂无|没有|不适用|未指定|不指定|不限|不需要)"
                    + "(?:[（(][^）)]*[）)])?$");

    /**
     * 否定/缺失语义提示词：整值出现否定语义且<b>不含</b>比较语义时判为"无"。
     * 用于覆盖 LLM 常见变体（"无特殊筛选""无明确筛选条件""全表统计（无筛选）"），
     * 而带比较语义的真实条件（如 {@code status='无'}）因命中 {@link #COMPARISON_HINT} 不会被误判。
     */
    private static final Pattern NEGATION_HINT = Pattern.compile(
            "无|暂无|没有|不适用|未指定|不指定|不需要|不限|none|\\bna\\b|\\bn/?a\\b|\\btbd\\b",
            Pattern.CASE_INSENSITIVE);

    /** 比较语义提示词：命中说明是真实筛选条件，不应被当作"无"。 */
    private static final Pattern COMPARISON_HINT = Pattern.compile(
            "=|<>|!=|>=|<=|>|<|\\bin\\b|\\blike\\b|\\bbetween\\b|\\bis\\b"
                    + "|等于|大于|小于|不小于|不大于|不等于|属于|介于|包含|不为|超过|低于|高于",
            Pattern.CASE_INSENSITIVE);

    /**
     * 表名提取时需丢弃的非表名英文 token（避免把描述性英文词误当表名）。
     * 除 SQL 关键字外，补充 none/tbd/need/confirm/context 等占位/描述词；正常下划线表名不受影响。
     */
    private static final Set<String> TABLE_STOP_WORDS = Set.of(
            "table", "tbl", "select", "from", "where", "and", "or", "not", "null",
            "by", "group", "order", "join", "left", "right", "inner", "outer", "as", "on",
            "none", "unknown", "tbd", "na", "no", "yes", "confirm", "need", "context",
            "candidate", "uncertain", "undefined", "todo", "pending", "maybe");

    private static final int ITEM_MAX_LEN = 200;

    private RcaPlanViewParser() {
    }

    /**
     * 解析规划文本为视图。
     *
     * @param planText RcaSqlPlanNode 输出的 Markdown 规划文本（可空）
     * @param question 用户原始问题（调用方传入，可空）
     * @param dsId     数据源 ID（调用方传入，可空）
     * @return 解析成功返回视图；文本为空/解析失败/无可对照信息返回 {@code null}
     */
    public static SqlPlanView parse(String planText, String question, String dsId) {
        if (planText == null || planText.isBlank()) {
            return null;
        }
        try {
            Map<String, String> sections = splitSections(planText);
            if (sections.isEmpty()) {
                return null;
            }
            SqlPlanView view = SqlPlanView.builder()
                    .question(question)
                    .dsId(dsId)
                    .targetTables(parseTargetTables(firstOf(sections, LABEL_CANDIDATE_TABLE, LABEL_TARGET_TABLE)))
                    .filters(parseList(sections.get(LABEL_FILTER), "[；;\\n]+"))
                    .dimensions(parseList(sections.get(LABEL_DIMENSION), "[，,、；;\\n]+"))
                    .metrics(parseList(sections.get(LABEL_AGGREGATE), "[，,、；;\\n]+"))
                    .timeRange(parseTimeRange(sections.get(LABEL_TIME_RANGE)))
                    .aggKernel(truncate(trimToNull(sections.get(LABEL_AGGREGATE)), ITEM_MAX_LEN))
                    .build();
            // 仅解析出"查询目标/风险提示"等无对照价值的章节时，视同解析失败返回 null，保持 L3 SKIP
            return hasComparableInfo(view) ? view : null;
        } catch (Exception e) {
            // 健壮性兜底：规划文本格式异常时不阻断主流程，退化为 L3 SKIP
            return null;
        }
    }

    /** 按章节标签切分 Markdown：标签行开启新章节，其余行归入当前章节（保留换行以便列表拆分）。 */
    private static Map<String, String> splitSections(String text) {
        Map<String, String> sections = new LinkedHashMap<>();
        String current = null;
        StringBuilder buf = new StringBuilder();
        for (String rawLine : text.split("\\r?\\n")) {
            String line = normalizeLine(rawLine);
            String label = matchLabel(line);
            if (label != null) {
                if (current != null) {
                    sections.put(current, buf.toString().trim());
                }
                current = label;
                buf.setLength(0);
                buf.append(trimSeparator(line.substring(label.length())));
            } else if (current != null) {
                if (buf.length() > 0) {
                    buf.append('\n');
                }
                buf.append(line);
            }
        }
        if (current != null) {
            sections.put(current, buf.toString().trim());
        }
        return sections;
    }

    /** 去除 Markdown 行内标记（列表符号、粗体、反引号、标题），得到"标签：值"形式。 */
    private static String normalizeLine(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        s = s.replaceAll("^[-*+•>]+\\s*", "");
        s = s.replaceAll("^\\d+[.)、]\\s*", "");
        s = s.replaceAll("^#+\\s*", "");
        s = s.replace("**", "").replace("__", "").replace("`", "");
        return s.trim();
    }

    /** 判断行首是否为已知标签（其后须为分隔符/空白/行尾，避免误匹配）。 */
    private static String matchLabel(String line) {
        for (String label : LABELS) {
            if (!line.startsWith(label)) {
                continue;
            }
            if (line.length() == label.length()) {
                return label;
            }
            char next = line.charAt(label.length());
            if (next == '：' || next == ':' || next == ' ' || next == '\t'
                    || next == '，' || next == ',' || next == '=') {
                return label;
            }
        }
        return null;
    }

    /** 去除标签后的分隔符与空白。 */
    private static String trimSeparator(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("^[\\s：:，,、=]+", "").trim();
    }

    /**
     * 主表解析：仅取括号前的候选区，且候选段内"恰有一个 SQL 标识符"才采纳为表名。
     *
     * <p>规划未确定主表时 LLM 常写"待确认, need confirm from context"这类占位/描述文本，
     * 若按 token 无差别提取会得到 need/confirm/context 等假表名，触发 L3"表不一致"硬 FAIL。
     * 故按分隔符切段：段内标识符数恰为 1 才采纳（0 个说明无表名，≥2 个说明是英文描述短语），
     * 命中占位/关键字表的 token 亦丢弃；无法可靠提取时宁可返回空列表（空则不参与表一致性判定）。</p>
     */
    private static List<String> parseTargetTables(String raw) {
        List<String> tables = new ArrayList<>();
        if (raw == null || raw.isBlank() || isNone(raw)) {
            return tables;
        }
        // 仅取首个左括号之前的候选区（括号内多为"依据/说明"文本，不应参与表名提取）
        String scope = raw;
        int cut = firstBracketIndex(raw);
        if (cut > 0) {
            scope = raw.substring(0, cut);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String segment : scope.split("[,，、;；/|]+")) {
            String candidate = segment.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            // 逐段统计英文标识符：恰为一个才视为表名候选（避免把英文描述短语拆成多个假表名）
            List<String> tokens = new ArrayList<>();
            Matcher matcher = IDENTIFIER.matcher(candidate);
            while (matcher.find()) {
                tokens.add(matcher.group());
            }
            if (tokens.size() != 1) {
                continue;
            }
            String token = tokens.get(0);
            if (TABLE_STOP_WORDS.contains(token.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (seen.add(token)) {
                tables.add(token);
            }
        }
        return tables;
    }

    /** 返回首个左括号（中/英文）下标，无则 -1。 */
    private static int firstBracketIndex(String raw) {
        int ch = raw.indexOf('（');
        int en = raw.indexOf('(');
        if (ch < 0) {
            return en;
        }
        if (en < 0) {
            return ch;
        }
        return Math.min(ch, en);
    }

    /** 列表字段解析：空/"无"→空列表；否则按分隔符拆分并逐项去重（含"无"项与跨标签污染项剔除）。 */
    private static List<String> parseList(String raw, String separatorRegex) {
        List<String> items = new ArrayList<>();
        if (raw == null || isNone(raw)) {
            return items;
        }
        for (String part : raw.split(separatorRegex)) {
            String value = truncate(part.trim(), ITEM_MAX_LEN);
            if (value.isEmpty() || isNone(value) || items.contains(value)) {
                continue;
            }
            // 多标签写在同一行时（如"筛选条件：无；分组维度：无"），后续标签文本不得并入当前字段
            if (matchLabel(value) != null) {
                continue;
            }
            items.add(value);
        }
        return items;
    }

    /**
     * 时间范围解析："未指定/无"→null；有日期 token 时取前两个作起止；否则保留自然语言摘要作起。
     */
    private static TimeRange parseTimeRange(String raw) {
        if (raw == null || raw.isBlank() || isNone(raw)) {
            return null;
        }
        List<String> dates = new ArrayList<>();
        Matcher matcher = DATE_TOKEN.matcher(raw);
        while (matcher.find()) {
            dates.add(matcher.group().replaceAll("\\s+", ""));
        }
        if (dates.size() >= 2) {
            return TimeRange.builder().start(dates.get(0)).end(dates.get(1)).build();
        }
        if (dates.size() == 1) {
            return TimeRange.builder().start(dates.get(0)).build();
        }
        // 无标准日期 token（如"近7天"）：不臆造起止，保留原文摘要
        return TimeRange.builder().start(truncate(raw.trim(), 100)).build();
    }

    private static String firstOf(Map<String, String> sections, String first, String second) {
        String v = sections.get(first);
        return v != null ? v : sections.get(second);
    }

    /** 是否存在可供 L3 对照的信息（主表/筛选/维度/指标/时间范围任一非空）。 */
    private static boolean hasComparableInfo(SqlPlanView view) {
        return !view.getTargetTables().isEmpty()
                || !view.getFilters().isEmpty()
                || !view.getDimensions().isEmpty()
                || !view.getMetrics().isEmpty()
                || view.getTimeRange() != null;
    }

    private static boolean isNone(String raw) {
        if (raw == null) {
            return true;
        }
        String s = raw.trim().replaceAll("[。.\\s]", "");
        if (s.isEmpty() || NONE_VALUE.matcher(s).matches()) {
            return true;
        }
        // 兜底：整值含否定/缺失语义且不含任何比较语义 → 视为"无"（覆盖"无特殊筛选""全表统计（无筛选）"等变体）
        return NEGATION_HINT.matcher(s).find() && !COMPARISON_HINT.matcher(raw).find();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
