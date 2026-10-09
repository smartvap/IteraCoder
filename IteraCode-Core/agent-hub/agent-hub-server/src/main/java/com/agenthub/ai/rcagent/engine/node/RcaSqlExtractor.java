package com.agenthub.ai.rcagent.engine.node;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RCAgent SQL 草案提取归一工具（纯静态工具类，无状态、无 Spring 依赖、可并发调用）。
 *
 * <p>背景（缺陷修复）：LLM 输出常带 Markdown 代码围栏（```` ```sql ... ``` ````）与结尾分号，
 * 既有实现把多轮原文直接拼接后送入 sqlguard，导致围栏内容被 JSqlParser 解析为多语句（MULTI）而误判 BLOCK；
 * 另有模型输出「代码自查通过 最终 SQL：...」等自然语言与 SQL 同行/多行混排的情况，
 * 说明文字一并入 sqlguard 会因首个 token 非 SQL 而解析失败（Encountered unexpected token），同样误判 BLOCK。
 * 本工具把「单轮原始输出」归一为可执行 SQL 草案：取最后一个围栏内容、剥离自然语言前后缀、去首尾空白、去结尾分号、去围栏残留标记。</p>
 *
 * <p>契约（FR-BE-001~FR-BE-003）：无状态纯函数；不抛异常；null/空白输入返回空串；对已归一文本幂等；
 * 不拼接/执行 SQL、不引入 SQL 解析依赖、不打印 SQL 全文（日志只允许记录长度）。</p>
 *
 * <p>安全（AGENTS.md 5.4）：SQL 草案属审核敏感内容，本类不输出输入/输出全文。</p>
 */
@Slf4j
public final class RcaSqlExtractor {

    /** Markdown 代码围栏标记（行首连续三个反引号） */
    private static final String FENCE_MARKER = "```";

    /**
     * 只读 SQL 起始关键字集合（大小写不敏感，需词边界）。
     *
     * <p>取舍说明：RCAgent 草案一律为只读查询，故只收录读类语句起始关键字
     * （SELECT / WITH / EXPLAIN / DESCRIBE / DESC / SHOW）；不收录 INSERT/UPDATE/DELETE 等写语句与 DDL，
     * 避免把自然语言里偶发的这些词误当作 SQL 起点（写类草案本就不允许通过 sqlguard 审核）。
     * DESCRIBE 置于 DESC 之前，保证正则交替匹配时优先命中更长关键字。</p>
     */
    private static final String SQL_START_KEYWORDS = "SELECT|WITH|EXPLAIN|DESCRIBE|DESC|SHOW";

    /** 行级定位：trim 后整行以读类关键字开头（词边界，避免 SELECTED 之类误命中） */
    private static final Pattern SQL_LINE_START_PATTERN = Pattern.compile("(?i)(" + SQL_START_KEYWORDS + ")\\b");

    /** 同行混排定位：首个处于词边界的读类关键字位置 */
    private static final Pattern SQL_KEYWORD_PATTERN = Pattern.compile("(?i)\\b(" + SQL_START_KEYWORDS + ")\\b");

    /** 判定一行是否含 ASCII 字母/数字（含则说明是 SQL 代码行而非纯中文说明） */
    private static final Pattern ASCII_ALNUM_PATTERN = Pattern.compile("[A-Za-z0-9]");

    /** SQL 注释行前缀（尾部清理时不得把注释行当说明行删除） */
    private static final String SQL_COMMENT_PREFIX = "--";

    /** SQL 结构符号：行内出现即视为代码行，避免把仅含 `)`/`;` 等 SQL 收尾行误判为说明行删除 */
    private static final String SQL_STRUCTURAL_CHARS = "()[]{},;='\"`*<>|/.";

    private RcaSqlExtractor() {
    }

    /**
     * 从「单轮」LLM 原始输出中提取归一后的 SQL 草案。
     *
     * <p>规则 R1：含 Markdown 围栏时取最后一个围栏内的内容；
     * R2：无围栏时取该轮文本本身；
     * R2'：对候选内容剥离自然语言前后缀（模型常输出「代码自查通过 最终 SQL：」等自查说明前缀），
     * 优先按行定位读类关键字起始行，行首未命中则按词边界正则定位同行混排的关键字，完全不含关键字则保留原候选；
     * R3：trim + 去结尾分号（含 `;` 后仅剩空白的场景）+ 去围栏残留标记（如孤立 ```` ``` ```` 行）；
     * R3'：循环去除尾部纯说明行（见 {@link #stripTrailingNaturalLanguageLines}）；
     * R4：结果为空返回空串（由调用方决定沿用上一轮或走兜底占位）。</p>
     *
     * @param rawOutput 单轮 LLM 原始输出（允许 null/空白；禁止传入多轮拼接结果）
     * @return 归一后的 SQL 文本；无可提取内容时返回空串
     */
    public static String extract(String rawOutput) {
        if (rawOutput == null) {
            return "";
        }
        try {
            List<String> fencedBlock = null;    // 最后一个围栏内的内容行（R1 候选）
            List<String> currentBlock = null;   // 当前正在累积的围栏内容行
            boolean inFence = false;
            List<String> outsideLines = new ArrayList<>();  // 围栏之外的文本行（R2 候选）

            for (String line : rawOutput.split("\n", -1)) {
                if (isFenceMarker(line)) {
                    if (inFence) {
                        fencedBlock = currentBlock;  // 围栏闭合：记录为「最后一个围栏内容」
                        currentBlock = null;
                        inFence = false;
                    } else {
                        inFence = true;
                        currentBlock = new ArrayList<>();
                    }
                    // 围栏标记行本身不属于 SQL 内容（消除残留标记）
                    continue;
                }
                if (inFence) {
                    currentBlock.add(line);
                } else {
                    outsideLines.add(line);
                }
            }
            // 末尾围栏未闭合（模型输出被截断）：已累积内容同样视为候选，避免丢弃真实草案
            if (inFence && currentBlock != null && !currentBlock.isEmpty()) {
                fencedBlock = currentBlock;
            }

            String candidate = String.join("\n", fencedBlock != null ? fencedBlock : outsideLines);
            // R2'：无论候选来自围栏还是围栏外，都先剥离自然语言前后缀，再进入 R3/R3' 清理
            return cleanup(stripNaturalLanguageAffixes(candidate));
        } catch (Exception e) {
            // 纯字符串处理理论上不抛异常；此处兜底保证「不抛异常」契约，且异常信息不含 SQL 文本
            log.warn("RCAgent SQL 草案提取异常，返回空串: {}", e.getClass().getSimpleName());
            return "";
        }
    }

    /** 是否为 Markdown 围栏标记行（行首连续三个反引号，可带语言标识） */
    private static boolean isFenceMarker(String line) {
        return line.trim().startsWith(FENCE_MARKER);
    }

    /**
     * R2'：剥离候选内容中的自然语言前后缀，定位真正的 SQL 语句。
     *
     * <p>为何需要：模型在一轮输出里可能先给「代码自查通过」等自查说明，再附「最终 SQL：」后给出语句，
     * 甚至说明与 SQL 同行混排；若把说明一并送入 sqlguard，JSqlParser 会因首个 token 非 SQL 而解析失败，
     * 导致把合法草案误判为 BLOCK。本方法只做「截断前缀」的纯字符串定位，不解析 SQL、不依赖任何 SQL 解析库。</p>
     *
     * <p>定位顺序：① 按行取第一条 trim 后以读类关键字开头的行，起至末尾；
     * ② 未命中则按词边界正则取首个关键字位置起的子串（处理「最终 SQL：SELECT ...」同行混排）；
     * ③ 完全不含关键字时保留原候选——避免把纯 SQL 片段或异常文本误清空。</p>
     */
    private static String stripNaturalLanguageAffixes(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return candidate;
        }
        String[] lines = candidate.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (SQL_LINE_START_PATTERN.matcher(lines[i].trim()).lookingAt()) {
                return String.join("\n", Arrays.copyOfRange(lines, i, lines.length));
            }
        }
        Matcher matcher = SQL_KEYWORD_PATTERN.matcher(candidate);
        if (matcher.find()) {
            return candidate.substring(matcher.start());
        }
        return candidate;
    }

    /** R3 + R3'：去除围栏残留标记行、首尾空白、结尾分号与尾部纯说明行（循环至稳定） */
    private static String cleanup(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
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
        // R3/R3' 交替循环至稳定：先清理尾部说明行、再去结尾分号，
        // 覆盖「分号后仍跟说明行」等组合场景（如 `SELECT 1;\n说明：`），保证幂等收敛
        String previous;
        do {
            previous = sql;
            sql = stripTrailingNaturalLanguageLines(sql);
            while (sql.endsWith(";")) {
                sql = sql.substring(0, sql.length() - 1).trim();
            }
        } while (!sql.equals(previous));
        return sql;
    }

    /**
     * R3'：循环去除尾部「纯说明行」。
     *
     * <p>纯说明行的判定：非空、不含任何 ASCII 字母/数字、不以 {@code --} 开头（SQL 注释需保留）、
     * 且不含 SQL 结构符号。之所以额外排除结构符号，是为了不误删仅含 `)`、`;` 等的合法 SQL 收尾行。</p>
     *
     * <p>安全阀：候选完全不含读类关键字时（无 SQL 的纯文本场景）直接跳过，
     * 避免把纯说明文本清理为空串（契约要求此类输入原样返回）；循环同时以「至少保留一行」为界。</p>
     */
    private static String stripTrailingNaturalLanguageLines(String sql) {
        if (sql == null || sql.isEmpty() || !containsSqlStartKeyword(sql)) {
            return sql;
        }
        List<String> lines = new ArrayList<>(Arrays.asList(sql.split("\n", -1)));
        boolean removedAny = false;
        while (lines.size() > 1) {
            String last = lines.get(lines.size() - 1).trim();
            if (last.isEmpty()
                    || last.startsWith(SQL_COMMENT_PREFIX)
                    || ASCII_ALNUM_PATTERN.matcher(last).find()
                    || containsSqlStructuralChar(last)) {
                break;
            }
            lines.remove(lines.size() - 1);
            removedAny = true;
        }
        return removedAny ? String.join("\n", lines).trim() : sql;
    }

    /** 文本中是否存在处于词边界的读类 SQL 起始关键字 */
    private static boolean containsSqlStartKeyword(String text) {
        return SQL_KEYWORD_PATTERN.matcher(text).find();
    }

    /** 行内是否含 SQL 结构符号（含则视为代码行，不得当作说明行删除） */
    private static boolean containsSqlStructuralChar(String line) {
        for (int i = 0; i < line.length(); i++) {
            if (SQL_STRUCTURAL_CHARS.indexOf(line.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }
}
