package com.agenthub.ai.rcagent.sqlguard.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * AST 解析与特征提取结果（data-model.md §3 SqlFeature，SPI S-1 出参）。
 *
 * <p>本结构为纯内存模型不落库；字段与 data-model §3 逐字一致，禁止弱类型替代。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlFeature {

    /** StatementType 枚举值（SELECT/DML/DDL/TRANSACTION/MULTI/UNKNOWN） */
    private String statementType;

    /** 形态分类：READ_ONLY / WRITE / DDL / TRANSACTION / MULTI / NON_SELECT */
    private String statementClass;

    /** 涉及表：{alias, name} */
    @Builder.Default
    private List<TableRef> tables = new ArrayList<>();

    /** 涉及列：{table(可空), column, isSelectItem, isWhere, isJoin, isAggregate} */
    @Builder.Default
    private List<ColumnRef> columns = new ArrayList<>();

    /** 是否含 SELECT * 或 SELECT t.* */
    private Boolean selectStar;

    /** WHERE 条件表达式个数（≈条件复杂度） */
    private Integer whereCount;

    /** JOIN 数量 */
    private Integer joinCount;

    /** JOIN 类型集合（INNER/LEFT/RIGHT/CROSS/FULL） */
    @Builder.Default
    private Set<String> joinTypeSet = new LinkedHashSet<>();

    /** 是否含子查询 */
    private Boolean hasSubquery;

    /** GROUP BY 项数 */
    private Integer groupByCount;

    /** ORDER BY 项数 */
    private Integer orderByCount;

    /** 是否显式 LIMIT/FETCH/ROWNUM 限制 */
    private Boolean hasLimit;

    /** LIMIT 数值（无则 null） */
    private Integer limitValue;

    /** 是否 DISTINCT */
    private Boolean hasDistinct;

    /** 聚合列/函数摘要（如 COUNT/CUSTOMER.ID、SUM/BAL_AMT） */
    @Builder.Default
    private List<String> aggregateColumns = new ArrayList<>();

    /**
     * SELECT 输出列别名集合（如 SUM(amt) AS total 中的 total）。
     *
     * <p>供 L2 未限定列存在性校验排除 ORDER BY / GROUP BY 引用输出别名时的误判
     * （ORDER BY 别名并非真实表列）。保持原始大小写，比较由消费方按需忽略大小写。</p>
     */
    @Builder.Default
    private List<String> outputAliases = new ArrayList<>();

    /**
     * 派生表（FROM/JOIN/IN 子查询，即内联视图）投影输出列别名集合（如 {@code FROM (SELECT COUNT(*) AS cnt ...) x} 中的 cnt）。
     *
     * <p>与 {@link #outputAliases} 分开放置：内联视图输出列在<b>任意子句</b>（含 SELECT 列表/WHERE/JOIN）
     * 均可被外层合法引用；而顶层 SELECT 输出别名仅在 ORDER BY/GROUP BY/HAVING 可引用。
     * 合并存放会导致 WHERE 引用输出别名被误放行，故单独存放，供 L2 未限定列校验判定。</p>
     */
    @Builder.Default
    private List<String> derivedColumns = new ArrayList<>();

    /** 是否为写/DDL 形态（DML/DDL/TRANSACTION/MULTI 含非 SELECT） */
    private Boolean writeIntent;

    /** Parser 方言降级/忽略片段警告（不构成文本匹配判据） */
    @Builder.Default
    private List<String> parseWarnings = new ArrayList<>();

    /**
     * 涉及表名（纯表名，不含 schema；保持原样不 upper，供审计 involved_tables 摘要与风险规则匹配）。
     */
    public List<String> tableNames() {
        List<String> names = new ArrayList<>();
        if (tables != null) {
            for (TableRef t : tables) {
                if (t != null && t.getName() != null && !t.getName().isBlank()) {
                    names.add(t.getName());
                }
            }
        }
        return names;
    }

    /**
     * 限定表名集合：有 schema 用 "schema.name"，否则纯表名（去重保序）。
     *
     * <p>与 {@link #tableNames()} 口径一致（保留原大小写、不做 upper），仅额外携带 schema 前缀。</p>
     */
    public List<String> qualifiedTableNames() {
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (tables != null) {
            for (TableRef t : tables) {
                if (t == null || t.getName() == null || t.getName().isBlank()) {
                    continue;
                }
                String qualified = t.qualifiedName();
                if (seen.add(qualified)) {
                    names.add(qualified);
                }
            }
        }
        return names;
    }

    /**
     * 涉及表引用（含 schema），供 L2 校验按限定名匹配。
     *
     * @return 表引用列表副本（无表时为空列表，非 null）
     */
    public List<TableRef> tableRefs() {
        return tables == null ? new ArrayList<>() : new ArrayList<>(tables);
    }

    public boolean selectStarFlag() {
        return Boolean.TRUE.equals(selectStar);
    }

    public boolean writeIntentFlag() {
        return Boolean.TRUE.equals(writeIntent);
    }

    public boolean subqueryFlag() {
        return Boolean.TRUE.equals(hasSubquery);
    }

    public boolean limitFlag() {
        return Boolean.TRUE.equals(hasLimit);
    }
}
