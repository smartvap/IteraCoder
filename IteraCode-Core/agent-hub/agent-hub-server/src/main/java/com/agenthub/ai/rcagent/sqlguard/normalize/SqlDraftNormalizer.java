package com.agenthub.ai.rcagent.sqlguard.normalize;

import com.agenthub.ai.dbaccess.model.DbType;
import com.agenthub.ai.rcagent.engine.node.RcaSqlExtractor;
import com.agenthub.ai.rcagent.sqlguard.constant.StatementClass;
import com.agenthub.ai.rcagent.sqlguard.exception.SqlGuardException;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import com.agenthub.ai.rcagent.sqlguard.parser.SqlParser;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RCAgent L2 统一 SQL 输出归一组件（唯一提取入口）。
 *
 * <p>把「单轮 LLM 原始输出」（散文 / Markdown 围栏 / JSON / 工具调用文本 / 纯 SQL）归一为可下游验证的纯 SQL 草案，
 * 并按 {@link SqlOutputIssue} 给出失败分类。分层降级策略（命中即返回，FR-BE-002）：</p>
 * <ol>
 *   <li>{@code S0} 结构化字段：来自 L1 结构化输出 / tool calling 参数（{@code RcaSqlDraftEntity.sql}）</li>
 *   <li>{@code S1} JSON 文本：输出为 JSON（含围栏包裹）且含 {@code sql} 字段</li>
 *   <li>{@code S2} Markdown 围栏：取<b>最后一个</b>代码围栏内容（支持未闭合围栏）</li>
 *   <li>{@code S3} 解析器容错滑窗：复用 {@link SqlParser} SPI，按「行首 + 行内词边界 SQL 起始关键字」起点滑窗，
 *       取最长且 {@link StatementClass#READ_ONLY} 的可解析片段；{@code MULTI} 继续尝试剥离为单语句</li>
 *   <li>{@code S4} 存量关键字定位：复用 {@link RcaSqlExtractor#extract(String)} 作为兼容降级层（不删除、不改签名）</li>
 *   <li>{@code S5} 轻量清洗：{@code trim} + 循环去尾分号 + 去残留围栏标记行</li>
 * </ol>
 *
 * <p><b>契约（api-contract.md 5.1）</b>：纯函数、无状态、可并发；不抛异常（任何内部异常与 {@link SqlGuardException}
 * 均捕获并按层降级）；幂等（{@code normalize(normalize(x).sql, db).sql == normalize(x).sql}）；
 * 不拼接 / 不执行 SQL，无数据库 / 网络访问；{@code rawOutput} 超 65535 字符先截断（对齐
 * {@code JsSqlParserAdapter} 的 SQL 长度上限）。</p>
 *
 * <p><b>安全（AGENTS.md 5.1/5.4）</b>：禁止打印 {@code rawOutput} / 归一结果全文；仅允许记录长度、层码、分类、耗时；
 * 本类<b>不标注</b> {@code @Loggable}，异常 message 不拼接原始输出全文。</p>
 */
@Component
public class SqlDraftNormalizer {

    private static final Logger log = LoggerFactory.getLogger(SqlDraftNormalizer.class);

    /** 未命中任何提取层 */
    public static final String LAYER_NONE = "NONE";
    /** 结构化字段层 */
    public static final String LAYER_S0 = "S0";
    /** JSON 文本层 */
    public static final String LAYER_S1 = "S1";
    /** Markdown 围栏层 */
    public static final String LAYER_S2 = "S2";
    /** 解析器容错滑窗层 */
    public static final String LAYER_S3 = "S3";
    /** 存量关键字定位层 */
    public static final String LAYER_S4 = "S4";
    /** 轻量清洗层 */
    public static final String LAYER_S5 = "S5";

    /** 原始输出长度上限（对齐 sqlguard SQL 长度上限，超限先截断再归一，NFR-BE-001） */
    private static final int MAX_INPUT_LENGTH = 65535;

    /** 结构化输出契约中的 SQL 字段名（S1 JSON 文本 / S0 实体共用，避免散落硬编码） */
    private static final String JSON_FIELD_SQL = "sql";

    /** S3 滑窗最大尝试次数硬上限（FR-BE-005） */
    private static final int MAX_ATTEMPTS_HARD = 200;

    /** S3 滑窗总耗时上限（毫秒，超限立即停止并降级 S4/S5，NFR-BE-001） */
    private static final long MAX_COST_MS = 200L;

    /**
     * 只读 SQL 起始关键字（大小写不敏感、词边界）。
     *
     * <p>复用 {@link RcaSqlExtractor} 的 {@code SQL_START_KEYWORDS} 思路（其常量为私有，无法跨包引用）：
     * 仅收录读类语句起始关键字，避免把自然语言里偶发的写类词误当作 SQL 起点；
     * DESCRIBE 置于 DESC 之前，保证交替匹配时优先命中更长关键字。</p>
     */
    private static final Pattern SQL_START_KEYWORD_PATTERN =
            Pattern.compile("(?i)\\b(SELECT|WITH|EXPLAIN|DESCRIBE|DESC|SHOW)\\b");

    private final SqlParser sqlParser;

    public SqlDraftNormalizer(SqlParser sqlParser) {
        this.sqlParser = sqlParser;
    }

    /**
     * 统一归一入口（文本层：S1 → S2 → S3 → S4 → S5）。
     *
     * @param rawOutput 单轮 LLM 原始输出（允许 null / 空白）
     * @param dbType    目标方言，供 S3 解析判定；为 null 时按 MYSQL 兜底（不失败）
     * @return 归一结果（字段非 null，{@code sql} 最少为空串）
     */
    public NormalizedSqlDraft normalize(String rawOutput, DbType dbType) {
        int rawLength = rawOutput == null ? 0 : rawOutput.length();
        long startNanos = System.nanoTime();
        try {
            if (rawOutput == null || rawOutput.isBlank()) {
                return NormalizedSqlDraft.of("", SqlOutputIssue.EMPTY, LAYER_NONE, rawLength);
            }
            String text = rawOutput.length() > MAX_INPUT_LENGTH
                    ? rawOutput.substring(0, MAX_INPUT_LENGTH) : rawOutput;

            // S1：JSON 文本（含围栏包裹的 JSON），含 sql 字段时直接取用；无 sql 字段明确判 NOT_SQL
            NormalizedSqlDraft jsonDraft = normalizeJsonText(text, dbType, rawLength);
            if (jsonDraft != null) {
                return logAndReturn(jsonDraft, startNanos);
            }

            // S2：取最后一个 Markdown 围栏内容（含未闭合围栏），命中即返回
            String fenced = lastFenceContent(text);
            if (fenced != null) {
                return logAndReturn(classifyCandidate(fenced, LAYER_S2, dbType, rawLength), startNanos);
            }

            // S3：解析器容错滑窗（最长可解析只读片段）
            NormalizedSqlDraft windowDraft = slidingWindow(text, dbType, rawLength);
            if (isTerminal(windowDraft)) {
                return logAndReturn(windowDraft, startNanos);
            }

            // S4：存量关键字定位（兼容降级层，新组件优先、存量兜底）
            String legacy = RcaSqlExtractor.extract(text);
            if (legacy != null && !legacy.isBlank()) {
                NormalizedSqlDraft legacyDraft = classifyCandidate(legacy, LAYER_S4, dbType, rawLength);
                if (isTerminal(legacyDraft)) {
                    return logAndReturn(legacyDraft, startNanos);
                }
            }

            // S5：轻量清洗（trim / 去尾分号 / 去残留围栏标记行）
            String cleaned = lightClean(text);
            if (!cleaned.isBlank()) {
                NormalizedSqlDraft cleanedDraft = classifyCandidate(cleaned, LAYER_S5, dbType, rawLength);
                if (isTerminal(cleanedDraft)) {
                    return logAndReturn(cleanedDraft, startNanos);
                }
            }

            // 全部未命中：沿用 S3 的失败分类（SYNTAX_ERROR / NOT_SQL）
            return logAndReturn(windowDraft, startNanos);
        } catch (Exception e) {
            // 「不抛异常」契约兜底：异常信息只含异常类名，不含原始输出全文
            log.warn("RCAgent SQL 归一异常(降级): type={}, rawLength={}", e.getClass().getSimpleName(), rawLength);
            return NormalizedSqlDraft.of("", SqlOutputIssue.NOT_SQL, LAYER_NONE, rawLength);
        }
    }

    /**
     * 统一归一入口（结构化层 S0）：直接消费 L1 结构化输出 / tool calling 参数。
     *
     * @param entity   结构化草案实体（可为 null）
     * @param dbType   目标方言（null 按 MYSQL 兜底）
     * @return 归一结果（S0 层）
     */
    public NormalizedSqlDraft normalize(RcaSqlDraftEntity entity, DbType dbType) {
        String sql = entity == null ? null : entity.getSql();
        int rawLength = sql == null ? 0 : sql.length();
        if (sql == null || sql.isBlank()) {
            return NormalizedSqlDraft.of("", SqlOutputIssue.NOT_SQL, LAYER_S0, rawLength);
        }
        try {
            return classifyCandidate(sql, LAYER_S0, dbType, rawLength);
        } catch (Exception e) {
            log.warn("RCAgent SQL 结构化字段归一异常(降级): type={}", e.getClass().getSimpleName());
            return NormalizedSqlDraft.of("", SqlOutputIssue.NOT_SQL, LAYER_S0, rawLength);
        }
    }

    /** 方言名解析：取不到时按 MYSQL 兜底（不失败，NFR-BE-001） */
    private static String resolveDbTypeName(DbType dbType) {
        return dbType == null ? DbType.MYSQL.getCode() : dbType.getCode();
    }

    /** 是否为「可终止」结果：成功 / 写形态 / 多语句（其余继续向下一层降级） */
    private static boolean isTerminal(NormalizedSqlDraft draft) {
        return draft.getIssue() == SqlOutputIssue.NONE
                || draft.getIssue() == SqlOutputIssue.NOT_READONLY
                || draft.getIssue() == SqlOutputIssue.MULTI;
    }

    // ==================== S1：JSON 文本 ====================

    /**
     * S1：把输出（可被围栏包裹）作为 JSON 解析并取 {@code sql} 字段。
     *
     * @return 命中返回归一结果；非 JSON / JSON 解析失败返回 null（继续下一层）
     */
    private NormalizedSqlDraft normalizeJsonText(String text, DbType dbType, int rawLength) {
        String fenced = lastFenceContent(text);
        String candidate = (fenced != null ? fenced : text).trim();
        if (!candidate.startsWith("{") || !candidate.endsWith("}")) {
            return null;
        }
        JSONObject json;
        try {
            json = JSON.parseObject(candidate);
        } catch (Exception e) {
            return null;
        }
        if (json == null) {
            return null;
        }
        if (!json.containsKey(JSON_FIELD_SQL)) {
            // JSON 结构合法但无 sql 字段：明确「不是 SQL」（不继续滑窗，避免把 rationale 文本误判为 SQL）
            return NormalizedSqlDraft.of("", SqlOutputIssue.NOT_SQL, LAYER_S1, rawLength);
        }
        Object value = json.get(JSON_FIELD_SQL);
        String sqlText = value == null ? "" : String.valueOf(value);
        return classifyCandidate(sqlText, LAYER_S1, dbType, rawLength);
    }

    // ==================== S2：Markdown 围栏 ====================

    /**
     * 取最后一个 Markdown 代码围栏内容（支持未闭合围栏）。
     *
     * @return 无围栏返回 null；有围栏返回其内容（可能为空串）
     */
    private static String lastFenceContent(String text) {
        List<String> lastBlock = null;
        List<String> current = null;
        boolean inFence = false;
        boolean sawFence = false;
        for (String line : text.split("\n", -1)) {
            if (isFenceMarker(line)) {
                sawFence = true;
                if (inFence) {
                    lastBlock = current;
                    current = null;
                    inFence = false;
                } else {
                    inFence = true;
                    current = new ArrayList<>();
                }
                continue;
            }
            if (inFence && current != null) {
                current.add(line);
            }
        }
        // 末尾围栏未闭合（输出被截断）：已累积内容同样视为候选，避免丢弃真实草案
        if (inFence && current != null && !current.isEmpty()) {
            lastBlock = current;
        }
        if (!sawFence) {
            return null;
        }
        return lastBlock == null ? "" : String.join("\n", lastBlock);
    }

    /** 是否为 Markdown 围栏标记行（trim 后以连续三个反引号开头，可带语言标识） */
    private static boolean isFenceMarker(String line) {
        return line != null && line.trim().startsWith("```");
    }

    // ==================== S3：解析器容错滑窗 ====================

    /**
     * S3：按候选起点（每行 trim 后非空行首 + 行内词边界 SQL 起始关键字位置）逐片段调用 {@link SqlParser#parse}，
     * 取<b>最长</b>的可解析只读片段；{@code MULTI} 继续尝试剥离为单语句。
     *
     * <p>上限保护：尝试次数 ≤ {@code 候选行数 × 2} 且硬上限 200，总耗时 ≤ 200ms（超限立即停止并降级 S4/S5）。</p>
     */
    private NormalizedSqlDraft slidingWindow(String text, DbType dbType, int rawLength) {
        long startNanos = System.nanoTime();
        List<Integer> starts = buildStartOffsets(text);
        int maxAttempts = Math.min(MAX_ATTEMPTS_HARD, Math.max(1, countNonBlankLines(text)) * 2);
        String bestReadOnly = null;
        int bestReadOnlyLen = -1;
        String bestWrite = null;
        int bestWriteLen = -1;
        boolean sawMulti = false;
        int attempts = 0;

        for (int start : starts) {
            if (attempts >= maxAttempts || elapsedMs(startNanos) > MAX_COST_MS) {
                break;
            }
            if (start < 0 || start >= text.length()) {
                continue;
            }
            String fragment = lightClean(text.substring(start));
            if (fragment.isEmpty()) {
                continue;
            }
            attempts++;
            FragmentOutcome outcome = resolveFragment(fragment, dbType);
            if (outcome == null) {
                // 解析器抛 SqlGuardException（方言不支持 / 参数非法 / 过长）：跳过该起点，不向上抛出
                continue;
            }
            switch (outcome.issue) {
                case NONE -> {
                    if (fragment.length() > bestReadOnlyLen) {
                        bestReadOnlyLen = fragment.length();
                        bestReadOnly = outcome.sql;
                    }
                }
                case NOT_READONLY -> {
                    if (fragment.length() > bestWriteLen) {
                        bestWriteLen = fragment.length();
                        bestWrite = outcome.sql;
                    }
                }
                case MULTI -> sawMulti = true;
                default -> {
                    // SYNTAX_ERROR / 其它：仅作为「有 SQL 起点但不可解析」的观测，不影响最长只读片段取舍
                }
            }
        }

        if (bestReadOnly != null) {
            return NormalizedSqlDraft.of(bestReadOnly, SqlOutputIssue.NONE, LAYER_S3, rawLength);
        }
        if (sawMulti) {
            return NormalizedSqlDraft.of("", SqlOutputIssue.MULTI, LAYER_S3, rawLength);
        }
        if (bestWrite != null) {
            return NormalizedSqlDraft.of(bestWrite, SqlOutputIssue.NOT_READONLY, LAYER_S3, rawLength);
        }
        // 无任何可解析结果：含读类起始关键字 → 语法错误；否则判「不是 SQL」（纯散文）
        boolean hasKeyword = SQL_START_KEYWORD_PATTERN.matcher(text).find();
        return NormalizedSqlDraft.of("", hasKeyword ? SqlOutputIssue.SYNTAX_ERROR : SqlOutputIssue.NOT_SQL,
                LAYER_S3, rawLength);
    }

    /** 生成候选起点：行首（trim 后非空）+ 行内词边界 SQL 起始关键字位置（去重升序） */
    private static List<Integer> buildStartOffsets(String text) {
        LinkedHashSet<Integer> offsets = new LinkedHashSet<>();
        int lineStart = 0;
        for (String line : text.split("\n", -1)) {
            String leadingStripped = line.stripLeading();
            if (!leadingStripped.isEmpty()) {
                offsets.add(lineStart + (line.length() - leadingStripped.length()));
            }
            lineStart += line.length() + 1;
        }
        Matcher matcher = SQL_START_KEYWORD_PATTERN.matcher(text);
        while (matcher.find()) {
            offsets.add(matcher.start());
        }
        List<Integer> result = new ArrayList<>(offsets);
        Collections.sort(result);
        return result;
    }

    private static int countNonBlankLines(String text) {
        int count = 0;
        for (String line : text.split("\n", -1)) {
            if (!line.isBlank()) {
                count++;
            }
        }
        return count;
    }

    /** 单片段解析结果（null 表示该起点被跳过） */
    private FragmentOutcome resolveFragment(String fragment, DbType dbType) {
        SqlFeature feature = safeParse(fragment, dbType);
        if (feature == null) {
            return null;
        }
        String statementClass = feature.getStatementClass();
        if (StatementClass.READ_ONLY.name().equals(statementClass)) {
            return FragmentOutcome.readOnly(fragment);
        }
        if (StatementClass.MULTI.name().equals(statementClass)) {
            return resolveMulti(fragment, dbType);
        }
        if (StatementClass.UNKNOWN.name().equals(statementClass)) {
            // UNKNOWN 时 JsSqlParserAdapter 会以文本辅助标记写形态；无该标记则视为语法错误
            return Boolean.TRUE.equals(feature.getWriteIntent())
                    ? FragmentOutcome.notReadOnly(fragment)
                    : FragmentOutcome.syntaxError();
        }
        // WRITE / DDL / TRANSACTION / NON_SELECT：不放行归一，交 sqlguard 按既有策略 BLOCK
        return FragmentOutcome.notReadOnly(fragment);
    }

    /**
     * MULTI 处理：先尝试按分号剥离为单语句，取其中最长的可解析只读片段；
     * 剥离失败（无只读单语句）则仍判 {@link SqlOutputIssue#MULTI}，交由上层回流。
     */
    private FragmentOutcome resolveMulti(String fragment, DbType dbType) {
        String best = null;
        for (String segment : fragment.split(";")) {
            String cleaned = lightClean(segment);
            if (cleaned.isEmpty()) {
                continue;
            }
            SqlFeature feature = safeParse(cleaned, dbType);
            if (feature == null) {
                continue;
            }
            if (StatementClass.READ_ONLY.name().equals(feature.getStatementClass())
                    && (best == null || cleaned.length() > best.length())) {
                best = cleaned;
            }
        }
        return best != null ? FragmentOutcome.readOnly(best) : FragmentOutcome.multi();
    }

    /** 调用解析器 SPI：任何异常均降级（返回 null 表示跳过该起点），不向上抛出 */
    private SqlFeature safeParse(String fragment, DbType dbType) {
        try {
            return sqlParser.parse(fragment, resolveDbTypeName(dbType));
        } catch (SqlGuardException e) {
            // 方言不支持 / 参数非法 / SQL 过长：跳过该起点即可，不阻断归一
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 候选分类 / 轻量清洗 ====================

    /**
     * 对「已锁定来源」的候选（S0/S1/S2/S4/S5）做清洗 + 解析分类。
     */
    private NormalizedSqlDraft classifyCandidate(String candidate, String layer, DbType dbType, int rawLength) {
        String cleaned = lightClean(candidate);
        if (cleaned.isEmpty()) {
            return NormalizedSqlDraft.of("", SqlOutputIssue.EMPTY, layer, rawLength);
        }
        FragmentOutcome outcome = resolveFragment(cleaned, dbType);
        if (outcome == null) {
            // 解析器不可用（方言 / 参数异常）：按语法错误分类降级，不抛出
            return NormalizedSqlDraft.of("", SqlOutputIssue.SYNTAX_ERROR, layer, rawLength);
        }
        return NormalizedSqlDraft.of(outcome.sql, outcome.issue, layer, rawLength);
    }

    /** S5 轻量清洗：trim + 循环去结尾分号 + 去残留围栏标记行（不做语义改写） */
    private static String lightClean(String candidate) {
        if (candidate == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String line : candidate.split("\n", -1)) {
            if (isFenceMarker(line)) {
                continue;
            }
            kept.add(line);
        }
        String sql = String.join("\n", kept).trim();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        return sql;
    }

    // ==================== 可观测（脱敏） ====================

    private NormalizedSqlDraft logAndReturn(NormalizedSqlDraft draft, long startNanos) {
        if (log.isDebugEnabled()) {
            // 仅记录长度 / 层码 / 分类 / 耗时，禁止 SQL 全文与原始输出（AGENTS.md 5.1/5.4）
            log.debug("RCAgent SQL 归一: layer={}, issue={}, rawLength={}, costMs={}",
                    draft.getExtractLayer(), draft.getIssue(), draft.getRawLength(), elapsedMs(startNanos));
        }
        return draft;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** 片段解析结果载体（内部） */
    private static final class FragmentOutcome {

        private final String sql;
        private final SqlOutputIssue issue;

        private FragmentOutcome(String sql, SqlOutputIssue issue) {
            this.sql = sql;
            this.issue = issue;
        }

        private static FragmentOutcome readOnly(String sql) {
            return new FragmentOutcome(sql, SqlOutputIssue.NONE);
        }

        private static FragmentOutcome notReadOnly(String sql) {
            return new FragmentOutcome(sql, SqlOutputIssue.NOT_READONLY);
        }

        private static FragmentOutcome multi() {
            return new FragmentOutcome("", SqlOutputIssue.MULTI);
        }

        private static FragmentOutcome syntaxError() {
            return new FragmentOutcome("", SqlOutputIssue.SYNTAX_ERROR);
        }
    }
}
