package com.agenthub.ai.dbaccess.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据源白名单（data-model.md WhitelistRule，对应 t_ds_config.whitelist_json）。
 *
 * <p>默认放行语义（用户已确认）：一个大系统几千张表时 fail-closed 白名单不现实，且操作员可能是
 * 运营/业务/测试人员，需要默认放开 + 黑名单 + 通配符，安全由「只读账号 + 敏感字段 DENY +
 * SELECT * 限制 + maxRows/maxSeconds」兜底。规则如下：</p>
 * <ul>
 *   <li><b>未配置白名单</b>（whitelist 为 null）→ 默认允许全部表（由调用方直接以 null 放行）；</li>
 *   <li>mode 缺省（null/blank）时：若 tables 非空 → 按 {@link #MODE_ALLOW_ONLY} 处理（兼容旧数据）；
 *       若 tables 为空 → 视为 {@link #MODE_ALL}（默认全部允许）；</li>
 *   <li>{@link #MODE_ALL}：任意表放行（忽略 tables/columns）；</li>
 *   <li>{@link #MODE_DENY_ONLY}：仅禁止列出的表，tables 为空 → 全部放行；</li>
 *   <li>{@link #MODE_ALLOW_ONLY}：仅允许列出的表，tables 为空 → 全拒（显式空白名单仍保留 fail-closed 能力）；</li>
 *   <li>表名支持通配符 {@code *} 与 {@code %}（等价，均视为任意字符序列），表名先 normalize 再匹配；</li>
 *   <li>列级限制（columns）仅对 {@link #MODE_ALLOW_ONLY} 的精确授权表生效；DENY_ONLY/ALL/通配授权表不启用列级拦截。</li>
 * </ul>
 *
 * <p>示例 JSON：
 * <pre>
 * { "tables": ["CUSTOMER", "CRM_*"], "columns": { "CUSTOMER": ["ID", "NAME"] }, "mode": "ALLOW_ONLY" }
 * { "tables": ["*_LOG", "%_TMP"], "mode": "DENY_ONLY" }
 * { "mode": "ALL" }
 * { "tables": [], "mode": "ALLOW_ONLY" }   // 显式空白名单：拒绝全部
 * </pre>
 * columns 可空/不含某表时表示该表整表放行（仅做表级校验）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhitelistRule {

    /** 模式：全部放行（忽略 tables/columns） */
    public static final String MODE_ALL = "ALL";

    /** 模式：仅允许列出的表（支持通配） */
    public static final String MODE_ALLOW_ONLY = "ALLOW_ONLY";

    /** 模式：仅禁止列出的表（支持通配） */
    public static final String MODE_DENY_ONLY = "DENY_ONLY";

    /** 合法模式集合（mode 缺省为 null/blank 时合法，见类注释兼容规则） */
    public static final Set<String> VALID_MODES = Set.of(MODE_ALLOW_ONLY, MODE_DENY_ONLY, MODE_ALL);

    /** 允许访问/禁止访问的表集合（支持 {@code *}/{@code %} 通配；未列出含义由 mode 决定） */
    private List<String> tables;

    /** 按表限制允许访问的列集合（可空=整表白名单；仅 ALLOW_ONLY 精确授权表启用列级限制） */
    private Map<String, List<String>> columns;

    /** 白名单模式：ALL / ALLOW_ONLY / DENY_ONLY（null/blank 视为缺省，见类注释） */
    private String mode;

    /**
     * 规范化表名/规则名（剥离成对引号并转大写，兼容 Oracle 大写存储；匹配大小写不敏感）。
     *
     * <p>统一处理三种引号风格：MySQL 反引号 {@code `x`}、ANSI 标准双引号 {@code "x"}、
     * SQL Server 方括号 {@code [x]}。仅剥离首尾成对引号，不成对时保留原样，避免误伤以引号
     * 字符开头/结尾的合法标识符。通配符 {@code *}/{@code %} 保留并随规则整体转大写。</p>
     */
    public static String normalizeTable(String table) {
        if (table == null) {
            return "";
        }
        String t = table.trim();
        if (t.length() >= 2 && ((t.startsWith("`") && t.endsWith("`"))
                || (t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("[") && t.endsWith("]")))) {
            t = t.substring(1, t.length() - 1);
        }
        return t.toUpperCase(Locale.ROOT);
    }

    /**
     * 当前是否可访问某表。
     *
     * @return true=放行，false=拒绝；语义见类注释（缺省 mode + tables 空视为全部允许）
     */
    public boolean allowTable(String table) {
        String m = normalizedMode();
        if (m == null || m.isEmpty()) {
            // 缺省：tables 非空 → 按 ALLOW_ONLY 兼容旧数据；tables 为空 → 默认全部允许
            if (tables == null || tables.isEmpty()) {
                return true;
            }
            return matchesAny(table, tables);
        }
        if (MODE_ALL.equals(m)) {
            return true;
        }
        if (MODE_DENY_ONLY.equals(m)) {
            // 黑名单：tables 为空 → 全部放行；命中任一 denylist 规则 → 拒绝
            if (tables == null || tables.isEmpty()) {
                return true;
            }
            return !matchesAny(table, tables);
        }
        // ALLOW_ONLY（显式；非法 mode 也回退到最保守的 allow-only，避免越权放行）
        if (tables == null || tables.isEmpty()) {
            return false;
        }
        return matchesAny(table, tables);
    }

    /**
     * 某表是否配置了列级限制（executor 仅在返回 true 时进入列级校验分支）。
     *
     * <p>列级限制仅对「有效 ALLOW_ONLY 且精确表键存在且无通配键命中」生效：
     * DENY_ONLY/ALL 不启用列级限制；表由通配授权（tables/columns 中存在匹配该表的
     * {@code *}/{@code %} 规则）或 columns 中无精确表键时返回 false（依赖表级校验 + 敏感字段兜底）。</p>
     */
    public boolean hasColumnRestriction(String table) {
        if (!effectiveAllowOnly()) {
            return false;
        }
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        String normalized = normalizeTable(table);
        if (normalized.isEmpty() || !hasExactColumnKey(table)) {
            return false;
        }
        // 表经通配授权时不做列级限制（通配列键命中同样视为通配管控，避免表-列映射歧义）
        return !hasWildcardMatch(tables, normalized) && !hasWildcardMatch(columns.keySet(), normalized);
    }

    /**
     * 当前是否可访问某表的某列。
     *
     * <p>仅当模式为有效 ALLOW_ONLY 且表级放行且该表命中精确列限制时做列校验；
     * DENY_ONLY/ALL 直接返回 true（不启用列级拦截）；未配置列限制/通配授权表返回 true。</p>
     */
    public boolean allowColumn(String table, String column) {
        if (!effectiveAllowOnly()) {
            // DENY_ONLY / ALL / 缺省 mode 且 tables 为空（视为 ALL）不启用列级限制
            return true;
        }
        if (!allowTable(table)) {
            return false;
        }
        if (!hasColumnRestriction(table)) {
            // 表未命中精确列限制（含通配授权）→ 整表放行
            return true;
        }
        List<String> allowed = columnsOf(table);
        if (allowed == null) {
            // 防御：columns key 存在但 value 为 null → 视为未配置列限制，整表放行
            return true;
        }
        if (allowed.isEmpty()) {
            // 显式空列白名单 → 拒绝全部列（保留 fail-closed 能力）
            return false;
        }
        String normalizedColumn = normalizeTable(column);
        for (String c : allowed) {
            if (normalizeTable(c).equals(normalizedColumn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 表名是否命中任一规则（精确 normalize 比较；规则含 {@code *}/{@code %} 时转正则整体匹配）。
     *
     * <p>配置项不含通配符 → 精确 normalize 比较；含通配 → 将 {@code *}/{@code %} 转为 {@code .*}
     * （其余字符 Pattern.quote 转义），整体 {@code ^...$} 匹配 normalize 后的表名。空规则忽略。</p>
     */
    private boolean matchesAny(String table, List<String> rules) {
        if (table == null || rules == null || rules.isEmpty()) {
            return false;
        }
        String normalized = normalizeTable(table);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String rule : rules) {
            if (rule == null) {
                continue;
            }
            String nr = normalizeTable(rule);
            if (nr.isEmpty()) {
                continue;
            }
            if (hasWildcard(nr)) {
                if (wildcardPattern(nr).matcher(normalized).matches()) {
                    return true;
                }
            } else if (nr.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 归一化 mode（trim 后比较，容忍 JSON/yml 中多余空白）；null → null */
    private String normalizedMode() {
        return mode == null ? null : mode.trim();
    }

    /**
     * 是否处于「有效 ALLOW_ONLY」：
     * 显式 mode=ALLOW_ONLY，或 mode 缺省且 tables 非空（旧数据兼容，等价 ALLOW_ONLY）。
     * DENY_ONLY / ALL / 缺省且 tables 为空（视为 ALL）返回 false。
     */
    private boolean effectiveAllowOnly() {
        String m = normalizedMode();
        if (m == null || m.isEmpty()) {
            return tables != null && !tables.isEmpty();
        }
        return MODE_ALLOW_ONLY.equals(m);
    }

    /** 是否含通配符 {@code *} 或 {@code %} */
    private boolean hasWildcard(String rule) {
        return rule != null && (rule.indexOf('*') >= 0 || rule.indexOf('%') >= 0);
    }

    /** 通配规则转锚定正则：{@code *}/{@code %} → {@code .*}，其余字符按字面转义 */
    private Pattern wildcardPattern(String rule) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < rule.length(); i++) {
            char ch = rule.charAt(i);
            if (ch == '*' || ch == '%') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    /** 是否存在含通配符且命中 normalize 后表名的规则（用于判定「通配授权」） */
    private boolean hasWildcardMatch(Collection<String> rules, String normalized) {
        if (rules == null || normalized == null || normalized.isEmpty()) {
            return false;
        }
        for (String rule : rules) {
            if (rule == null) {
                continue;
            }
            String nr = normalizeTable(rule);
            if (!nr.isEmpty() && hasWildcard(nr) && wildcardPattern(nr).matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    /** 是否存在 normalize 后精确等于某表的 columns key（通配列键不算精确 key） */
    private boolean hasExactColumnKey(String table) {
        if (columns == null || table == null) {
            return false;
        }
        String normalized = normalizeTable(table);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String key : columns.keySet()) {
            if (key != null && normalizeTable(key).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 取 normalize 后精确匹配某表的列限制列表；无精确表键 → null（通配列键不参与精确列限制） */
    private List<String> columnsOf(String table) {
        if (columns == null || table == null) {
            return null;
        }
        String normalized = normalizeTable(table);
        if (normalized.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : columns.entrySet()) {
            if (entry.getKey() != null && normalizeTable(entry.getKey()).equals(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 从 JSON 解析（数据库 JSON 字段 / yml 字符串兜底）。
     *
     * @param json 可空
     */
    public static WhitelistRule fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JSON.parseObject(json, WhitelistRule.class);
    }

    /** 序列化为 JSON 文本（供日志/落库使用，不含任何凭据） */
    @JSONField(serialize = false)
    public String toJson() {
        return JSON.toJSONString(this);
    }

    /**
     * 是否「显式全拒」：仅当 mode 为 ALLOW_ONLY 且 tables 为空时返回 true。
     *
     * <p>其余情况（null/ALL/DENY_ONLY/ALLOW_ONLY+非空清单）均视为非显式全拒，
     * 供启用门槛等判断「白名单是否满足」使用。</p>
     *
     * <p>语义对照 {@link #allowTable}：mode 缺省（null/blank）+ tables 空 → allowTable 默认全部放行
     * （相当于 ALL），此处返回 false（非全拒，正确）；mode 缺省 + tables 非空 → allowTable 按
     * ALLOW_ONLY 兼容旧数据（命中放行），说明已配置清单，BR-001 应满足，此处返回 false（正确）。
     * 因此仅「显式 mode=ALLOW_ONLY 且 tables 为空/null」才算显式全拒，{@link #empty()} 亦被识别。</p>
     */
    public boolean isExplicitRejectAll() {
        String m = mode == null ? null : mode.trim();
        if (MODE_ALLOW_ONLY.equals(m)) {
            return tables == null || tables.isEmpty();
        }
        return false;
    }

    /**
     * 显式空白名单（mode=ALLOW_ONLY + 空 tables → 拒绝全部表，保留 fail-closed 能力）。
     *
     * <p>与「未配置（null）→ 默认允许全部」形成对比，需要显式全拒的数据源应使用本方法表达语义。</p>
     */
    public static WhitelistRule empty() {
        return WhitelistRule.builder()
                .tables(new ArrayList<>())
                .columns(new LinkedHashMap<>())
                .mode(MODE_ALLOW_ONLY)
                .build();
    }
}
