package com.agenthub.ai.rcagent.engine.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * RCAgent 元数据/结构类问题确定性判定器（rca1.4.0.0 metadata-qa-routing）。
 *
 * <p>背景（真实故障）：用户提问「workflow_metadata表的索引有哪些？」被意图节点判为 {@code QUERY}，
 * 于是生成 {@code information_schema} 查询，被 SQL 安全体系 L2 判未知表后 {@code BLOCK} → 会话
 * {@code FAILED}；而检索侧其实已召回索引/主键证据（{@code kb_index} 3 条含 {@code PRIMARY(thread_id)}）。
 * 本判定器在委托 LLM 之前先做确定性识别，命中即以 {@code CHAT} 短路，使元数据问题走
 * {@code rcagent_answer}（知识库证据作答）而不再生成 SQL。</p>
 *
 * <p>判定优先级（严格按序执行，S0~S6，禁止调序）：
 * P1 查数信号（{@code MD-Q-*}）<b>绝对优先</b>否决元数据判定 → P2 元数据强信号（{@code MD-M-*}）
 * → P3 元数据弱信号（{@code MD-W-*}，须与询问词共现）→ 其余未命中交 LLM 兜底。
 * 「workflow_metadata 表状态为失败的数据有多少条？」这类含元数据名词的查数问法因此保持 {@code QUERY}（零回归）。</p>
 *
 * <p>约束：纯函数（无状态、无 IO、无线程共享可变状态），{@code null}/空白/超长/含正则元字符输入
 * <b>禁止抛异常</b>；规则以命名常量集中声明（规则码与 {@code data-model.md} §1「规则明细」逐条对应），
 * 规则码重复在类初始化期即失败（对应逻辑模型 {@code t_meta_question_rule.uk_rule_code}）。</p>
 */
public final class RcaMetadataQuestionDetector {

    /** 无状态单例（运行期恒等，供图配置与适配器复用） */
    public static final RcaMetadataQuestionDetector INSTANCE = new RcaMetadataQuestionDetector();

    // ===== 规则码常量（与 data-model.md §1「规则明细」逐条一致）=====

    /** 查数信号：多少（例：workflow_metadata表状态为失败的数据有多少条） */
    public static final String MD_Q_01 = "MD-Q-01";
    /** 查数信号：几[条个次位张行] */
    public static final String MD_Q_02 = "MD-Q-02";
    /** 查数信号：数量 */
    public static final String MD_Q_03 = "MD-Q-03";
    /** 查数信号：条数 */
    public static final String MD_Q_04 = "MD-Q-04";
    /** 查数信号：记录数 */
    public static final String MD_Q_05 = "MD-Q-05";
    /** 查数信号：总数 */
    public static final String MD_Q_06 = "MD-Q-06";
    /** 查数信号：统计 */
    public static final String MD_Q_07 = "MD-Q-07";
    /** 查数信号：求和 */
    public static final String MD_Q_08 = "MD-Q-08";
    /** 查数信号：合计 */
    public static final String MD_Q_09 = "MD-Q-09";
    /** 查数信号：平均(值|数)? */
    public static final String MD_Q_10 = "MD-Q-10";
    /** 查数信号：最大值 */
    public static final String MD_Q_11 = "MD-Q-11";
    /** 查数信号：最小值 */
    public static final String MD_Q_12 = "MD-Q-12";
    /** 查数信号：明细 */
    public static final String MD_Q_13 = "MD-Q-13";
    /** 查数信号：top N */
    public static final String MD_Q_14 = "MD-Q-14";
    /** 查数信号：排名 */
    public static final String MD_Q_15 = "MD-Q-15";
    /** 查数信号：占比 */
    public static final String MD_Q_16 = "MD-Q-16";
    /** 查数信号：比例 */
    public static final String MD_Q_17 = "MD-Q-17";
    /** 查数信号：趋势 */
    public static final String MD_Q_18 = "MD-Q-18";
    /** 查数信号：同比 */
    public static final String MD_Q_19 = "MD-Q-19";
    /** 查数信号：环比 */
    public static final String MD_Q_20 = "MD-Q-20";
    /** 查数信号：近 N 天/日/周/月/年 */
    public static final String MD_Q_21 = "MD-Q-21";
    /** 查数信号：本月/上月/去月/今月等时间窗口 */
    public static final String MD_Q_22 = "MD-Q-22";
    /** 查数信号：每天/每日/每月/每周/每年 */
    public static final String MD_Q_23 = "MD-Q-23";
    /** 查数信号：按天/日/月/周/年/季度/小时 */
    public static final String MD_Q_24 = "MD-Q-24";
    /** 元数据强信号：索引 → INDEX */
    public static final String MD_M_01 = "MD-M-01";
    /** 元数据强信号：主键 / primary key → PRIMARY_KEY */
    public static final String MD_M_02 = "MD-M-02";
    /** 元数据强信号：外键 / foreign key → FOREIGN_KEY */
    public static final String MD_M_03 = "MD-M-03";
    /** 元数据强信号：唯一键 / 唯一索引 / 唯一约束 / unique → UNIQUE_KEY */
    public static final String MD_M_04 = "MD-M-04";
    /** 元数据强信号：表结构 / 表定义 / 表设计 / 建表 / DDL / schema / 元数据 → TABLE_DEF */
    public static final String MD_M_05 = "MD-M-05";
    /** 元数据强信号：字段定义 / 列定义 / 字段类型 / 列类型 / 字段说明 / 字段含义 / 列说明 → COLUMN_DEF */
    public static final String MD_M_06 = "MD-M-06";
    /** 元数据强信号：约束 / 非空约束 / not null → CONSTRAINT */
    public static final String MD_M_07 = "MD-M-07";
    /** 元数据强信号：表关系 / 关联关系 / 外键关系 / 关联字段 → RELATION */
    public static final String MD_M_08 = "MD-M-08";
    /** 元数据弱信号：字段 / 列（须与询问词共现）→ COLUMN_DEF */
    public static final String MD_W_01 = "MD-W-01";
    /** 元数据弱信号：默认值（须与询问词共现）→ COLUMN_DEF */
    public static final String MD_W_02 = "MD-W-02";
    /** 元数据弱信号：表（须与询问词共现）→ TABLE_DEF */
    public static final String MD_W_03 = "MD-W-03";

    /** 未命中原因：空问题（S0） */
    private static final String REASON_EMPTY_QUESTION = "EMPTY_QUESTION";
    /** 未命中原因前缀：查数信号否决元数据判定（S3） */
    private static final String REASON_QUERY_SIGNAL_PREFIX = "QUERY_SIGNAL:";
    /** 命中原因：元数据强信号（S4） */
    private static final String REASON_METADATA_STRONG = "METADATA_STRONG";
    /** 命中原因：元数据弱信号 + 询问词共现（S5） */
    private static final String REASON_METADATA_WEAK = "METADATA_WEAK";
    /** 未命中原因：无规则命中（S6，交 LLM 兜底） */
    private static final String REASON_NO_RULE_HIT = "NO_RULE_HIT";

    /** 判定优先级：P1 查数信号（绝对优先否决元数据判定） */
    private static final int PRIORITY_QUERY_SIGNAL = 1;
    /** 判定优先级：P2 元数据强信号 */
    private static final int PRIORITY_METADATA_STRONG = 2;
    /** 判定优先级：P3 元数据弱信号（须与询问词共现） */
    private static final int PRIORITY_METADATA_WEAK = 3;

    /** 长度保护：仅匹配问题前 2000 字符（防超长输入正则扫描开销，与既有提示词截断口径一致） */
    private static final int MAX_MATCH_LENGTH = 2000;

    /** 正则统一大小写不敏感（问题文本已小写归一，双保险） */
    private static final int MATCH_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /**
     * 询问词（S5 弱信号共现条件，对应 data-model.md §1 D 表「询问词-01~05」）：
     * 有哪些 / 是什么 / 包含哪些 / 清单 / 定义 五类合并为单个预编译 Pattern。
     */
    private static final Pattern QUESTION_WORD = regex(
            "有哪些|有哪几个|哪些"
                    + "|是什么|都是什么|分别是什么|分别是"
                    + "|包含哪些|包括哪些|都有什么"
                    + "|清单|列表|一览"
                    + "|定义|结构|说明|含义");

    /**
     * 元数据弱信号规则（P3：{@code require_question_word=1}，须与询问词共现）。
     * <p>评估顺序按「主体词具体度」排列：{@code 默认值} → {@code 字段|列} → {@code 表}，
     * 以保证「status 字段的默认值是什么？」命中更具体的 {@code MD-W-02}（COLUMN_DEF/默认值），
     * 而「workflow_metadata 有哪些字段？」命中 {@code MD-W-01}、「这个数据源有哪些表」命中 {@code MD-W-03}。</p>
     */
    private static final List<Rule> METADATA_WEAK_RULES = List.of(
            rule(MD_W_02, PRIORITY_METADATA_WEAK, MetadataCategory.COLUMN_DEF, regex("默认值")),
            rule(MD_W_01, PRIORITY_METADATA_WEAK, MetadataCategory.COLUMN_DEF, regex("字段|列")),
            rule(MD_W_03, PRIORITY_METADATA_WEAK, MetadataCategory.TABLE_DEF, regex("表")));

    /**
     * 元数据强信号规则（P2：命中即判元数据问题）。
     * <p>评估顺序按「表达式具体度」排列（{@code 唯一索引} 先于 {@code 索引}、{@code 外键关系} 先于 {@code 外键}），
     * 使 data-model.md §1 B 表各条 example_hit 与其 rule_code 一致；同一问题命中多条强信号时取更具体的类别，
     * 避免落入更宽泛的 {@code MD-M-01}/{@code MD-M-03}。</p>
     */
    private static final List<Rule> METADATA_STRONG_RULES = List.of(
            rule(MD_M_04, PRIORITY_METADATA_STRONG, MetadataCategory.UNIQUE_KEY,
                    regex("唯一键|唯一索引|唯一约束|unique\\s*(key|index)?")),
            rule(MD_M_02, PRIORITY_METADATA_STRONG, MetadataCategory.PRIMARY_KEY,
                    regex("主键|primary\\s*key|primary")),
            rule(MD_M_08, PRIORITY_METADATA_STRONG, MetadataCategory.RELATION,
                    regex("表关系|关联关系|外键关系|关联字段")),
            rule(MD_M_03, PRIORITY_METADATA_STRONG, MetadataCategory.FOREIGN_KEY,
                    regex("外键|foreign\\s*key")),
            rule(MD_M_01, PRIORITY_METADATA_STRONG, MetadataCategory.INDEX, keyword("索引")),
            rule(MD_M_06, PRIORITY_METADATA_STRONG, MetadataCategory.COLUMN_DEF,
                    regex("字段定义|列定义|字段类型|列类型|字段说明|字段含义|列说明")),
            rule(MD_M_07, PRIORITY_METADATA_STRONG, MetadataCategory.CONSTRAINT,
                    regex("约束|非空约束|not\\s*null")),
            rule(MD_M_05, PRIORITY_METADATA_STRONG, MetadataCategory.TABLE_DEF,
                    regex("表结构|表定义|表设计|建表|ddl|\\bschema\\b|元数据")));

    /** 查数信号规则（P1：命中即否决元数据判定，保持既有 QUERY 路径） */
    private static final List<Rule> QUERY_SIGNAL_RULES = List.of(
            rule(MD_Q_01, PRIORITY_QUERY_SIGNAL, null, keyword("多少")),
            rule(MD_Q_02, PRIORITY_QUERY_SIGNAL, null, regex("几[条个次位张行]")),
            rule(MD_Q_03, PRIORITY_QUERY_SIGNAL, null, keyword("数量")),
            rule(MD_Q_04, PRIORITY_QUERY_SIGNAL, null, keyword("条数")),
            rule(MD_Q_05, PRIORITY_QUERY_SIGNAL, null, keyword("记录数")),
            rule(MD_Q_06, PRIORITY_QUERY_SIGNAL, null, keyword("总数")),
            rule(MD_Q_07, PRIORITY_QUERY_SIGNAL, null, keyword("统计")),
            rule(MD_Q_08, PRIORITY_QUERY_SIGNAL, null, keyword("求和")),
            rule(MD_Q_09, PRIORITY_QUERY_SIGNAL, null, keyword("合计")),
            rule(MD_Q_10, PRIORITY_QUERY_SIGNAL, null, regex("平均(值|数)?")),
            rule(MD_Q_11, PRIORITY_QUERY_SIGNAL, null, keyword("最大值")),
            rule(MD_Q_12, PRIORITY_QUERY_SIGNAL, null, keyword("最小值")),
            rule(MD_Q_13, PRIORITY_QUERY_SIGNAL, null, keyword("明细")),
            rule(MD_Q_14, PRIORITY_QUERY_SIGNAL, null, regex("top\\s*\\d+")),
            rule(MD_Q_15, PRIORITY_QUERY_SIGNAL, null, keyword("排名")),
            rule(MD_Q_16, PRIORITY_QUERY_SIGNAL, null, keyword("占比")),
            rule(MD_Q_17, PRIORITY_QUERY_SIGNAL, null, keyword("比例")),
            rule(MD_Q_18, PRIORITY_QUERY_SIGNAL, null, keyword("趋势")),
            rule(MD_Q_19, PRIORITY_QUERY_SIGNAL, null, keyword("同比")),
            rule(MD_Q_20, PRIORITY_QUERY_SIGNAL, null, keyword("环比")),
            rule(MD_Q_21, PRIORITY_QUERY_SIGNAL, null, regex("近\\s*\\d+\\s*(天|日|周|月|年)")),
            rule(MD_Q_22, PRIORITY_QUERY_SIGNAL, null, regex("(本|上|去|今)(月|季度|年|周|日)")),
            rule(MD_Q_23, PRIORITY_QUERY_SIGNAL, null, regex("每天|每日|每月|每周|每年")),
            rule(MD_Q_24, PRIORITY_QUERY_SIGNAL, null, regex("按(天|日|月|周|年|季度|小时)")));

    /**
     * 规则登记表：键为规则码（保证唯一，重复即初始化失败），插入顺序即判定优先级顺序
     * （P1 查数信号 → P2 强信号 → P3 弱信号）。
     */
    private static final Map<String, Rule> RULES = buildRules();

    private RcaMetadataQuestionDetector() {
    }

    /**
     * 判定问题是否为元数据/结构类问题（纯函数，同一输入恒返回同一结果）。
     *
     * @param question 用户原始问题（可为 null）
     * @return 判定结果；{@code metadata=false} 时 {@code ruleCode}/{@code category} 均为 null
     */
    public Detection detect(String question) {
        // S0：空输入安全——交 LLM 兜底，不抛异常
        if (question == null || question.strip().isEmpty()) {
            return Detection.miss(REASON_EMPTY_QUESTION);
        }
        // S1：小写归一（保留原文用于长度截断）
        // S2：仅匹配前 2000 字符，防超长输入
        String text = question.toLowerCase(Locale.ROOT);
        if (text.length() > MAX_MATCH_LENGTH) {
            text = text.substring(0, MAX_MATCH_LENGTH);
        }
        // S3：P1 查数信号绝对优先——命中即否决元数据判定（避免把查数误判为元数据问题）
        for (Rule rule : RULES.values()) {
            if (rule.priority() == PRIORITY_QUERY_SIGNAL && rule.matches(text)) {
                return Detection.miss(REASON_QUERY_SIGNAL_PREFIX + rule.code());
            }
        }
        // S4：P2 元数据强信号——命中即判元数据问题
        for (Rule rule : RULES.values()) {
            if (rule.priority() == PRIORITY_METADATA_STRONG && rule.matches(text)) {
                return Detection.hit(rule.code(), rule.category(), REASON_METADATA_STRONG);
            }
        }
        // S5：P3 元数据弱信号——须与询问词共现（覆盖「某表有哪些字段」类问法）
        if (QUESTION_WORD.matcher(text).find()) {
            for (Rule rule : RULES.values()) {
                if (rule.priority() == PRIORITY_METADATA_WEAK && rule.matches(text)) {
                    return Detection.hit(rule.code(), rule.category(), REASON_METADATA_WEAK);
                }
            }
        }
        // S6：无规则命中——交 LLM 兜底
        return Detection.miss(REASON_NO_RULE_HIT);
    }

    /** 便捷方法：与 {@link #detect(String)} 结果严格一致 */
    public boolean isMetadataQuestion(String question) {
        return detect(question).metadata();
    }

    private static Map<String, Rule> buildRules() {
        Map<String, Rule> map = new LinkedHashMap<>();
        register(map, QUERY_SIGNAL_RULES);
        register(map, METADATA_STRONG_RULES);
        register(map, METADATA_WEAK_RULES);
        return Collections.unmodifiableMap(map);
    }

    private static void register(Map<String, Rule> map, List<Rule> rules) {
        for (Rule rule : rules) {
            if (map.put(rule.code(), rule) != null) {
                // 规则码重复属代码缺陷：类初始化期即暴露（对应逻辑模型 uk_rule_code 唯一约束）
                throw new IllegalStateException("RCAgent 元数据判定规则码重复：" + rule.code());
            }
        }
    }

    private static Rule rule(String code, int priority, MetadataCategory category, Pattern pattern) {
        return new Rule(code, priority, category, pattern);
    }

    /** 关键词匹配规则（子串，大小写不敏感） */
    private static Pattern keyword(String literal) {
        return Pattern.compile(Pattern.quote(literal), MATCH_FLAGS);
    }

    /** 正则匹配规则 */
    private static Pattern regex(String source) {
        return Pattern.compile(source, MATCH_FLAGS);
    }

    /**
     * 元数据子类（逻辑字段 {@code metadata_category}，取值封闭）。
     */
    public enum MetadataCategory {
        /** 索引 */
        INDEX,
        /** 主键 */
        PRIMARY_KEY,
        /** 外键 */
        FOREIGN_KEY,
        /** 唯一键 / 唯一索引 / 唯一约束 */
        UNIQUE_KEY,
        /** 表结构 / 表定义 / DDL / Schema / 元数据 */
        TABLE_DEF,
        /** 字段 / 列定义、类型、说明、默认值 */
        COLUMN_DEF,
        /** 约束 / 非空 */
        CONSTRAINT,
        /** 表关系 / 关联关系 */
        RELATION
    }

    /**
     * 判定结果（不可变；未命中时 {@code ruleCode}/{@code category} 为 null）。
     */
    public record Detection(boolean metadata, String ruleCode, MetadataCategory category, String reason) {

        /** 命中工厂 */
        public static Detection hit(String ruleCode, MetadataCategory category, String reason) {
            return new Detection(true, ruleCode, category, reason);
        }

        /** 未命中工厂（ruleCode/category 恒为 null） */
        public static Detection miss(String reason) {
            return new Detection(false, null, null, reason);
        }
    }

    /**
     * 单条判定规则（内部承载，对应逻辑模型 {@code t_meta_question_rule} 一行）。
     *
     * @param code     规则码（唯一）
     * @param priority 判定优先级（1 查数信号 / 2 强信号 / 3 弱信号）
     * @param category 元数据子类（查数信号为 null）
     * @param pattern  预编译匹配表达式（关键词用 {@link Pattern#quote} 转义，问题文本永不被当作正则）
     */
    private record Rule(String code, int priority, MetadataCategory category, Pattern pattern) {

        /** 问题文本（已小写归一、已截断）是否命中本规则 */
        boolean matches(String text) {
            return pattern.matcher(text).find();
        }
    }
}
