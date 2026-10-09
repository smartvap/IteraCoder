package com.agenthub.ai.rcagent.feedback.analyzer;

import com.agenthub.ai.rcagent.feedback.adapter.SqlGuardAdapter;
import com.agenthub.ai.rcagent.feedback.util.FeedbackSafeText;
import com.agenthub.ai.rcagent.sqlguard.model.ColumnRef;
import com.agenthub.ai.rcagent.sqlguard.model.SqlFeature;
import com.agenthub.ai.rcagent.sqlguard.model.TableRef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 双 AST 确定性差异分析（FR-BE-002）。
 *
 * <p>对 generatedSql / modifiedSql 复用 module-006 {@link SqlGuardService#parse} 获取
 * SqlFeature 后做确定性对比：表集合 / 列集合 / where 时间相关列 / JOIN 数量与类型 /
 * 聚合与分组变化，产出 {@link DiffSummary}（不含 SQL 原文）。
 * 当 modifiedSql 缺失、parse 不可用（SqlGuardAdapter 返回 null）或语句为非 SELECT 时，
 * 降级为归一化文本 diff（空白折叠 + 小写比对），textDiffDegraded=true 供上层决策。</p>
 */
@Slf4j
@Component
public class SqlDiffAnalyzer {

    private static final Pattern TIME_COLUMN_PATTERN = Pattern.compile(
            "(time|date|month|year|day|时间|日期|月份|年度|日期段|期间)", Pattern.CASE_INSENSITIVE);

    private final SqlGuardAdapter sqlGuardAdapter;

    public SqlDiffAnalyzer(SqlGuardAdapter sqlGuardAdapter) {
        this.sqlGuardAdapter = sqlGuardAdapter;
    }

    /**
     * 分析 generatedSql 与 modifiedSql 差异。
     *
     * @param generatedSql 系统生成 SQL（可空）
     * @param modifiedSql  用户修正 SQL（空 → 无法 AST diff）
     * @param dbType       SQL 方言（MYSQL/ORACLE/OCEANBASE_MYSQL/OCEANBASE_ORACLE）
     */
    public DiffSummary analyze(String generatedSql, String modifiedSql, String dbType) {
        if (modifiedSql == null || modifiedSql.isBlank()) {
            // 无修正 SQL：无 diff 可分析，仅 AST 成功空摘要
            DiffSummary empty = new DiffSummary();
            empty.setAstParsed(false);
            return empty;
        }
        SqlFeature gen = sqlGuardAdapter.parse(generatedSql, dbType);
        SqlFeature mod = sqlGuardAdapter.parse(modifiedSql, dbType);
        if (gen == null || mod == null || !isSelectFeature(gen) || !isSelectFeature(mod)) {
            return textDiff(generatedSql, modifiedSql);
        }
        DiffSummary summary = new DiffSummary();
        summary.setAstParsed(true);
        summary.setTextDiffDegraded(false);
        summary.setChangedTables(diffTableNames(gen, mod));
        summary.setChangedColumns(diffColumnKeys(gen, mod));
        summary.setAddedWhere(diffTimeWhereColumns(gen, mod));
        summary.setJoinChanged(diffJoinChanged(gen, mod));
        // JOIN_ERROR → kb_relation 回写闭环：记录 JOIN ON 关联键列变化明细与修正侧关联列，
        // 供 KnowledgeBuilder 填充 module-004 saveRelation 必填的 table_a/table_b/column_a/column_b
        summary.setChangedJoinColumns(diffJoinColumnKeys(gen, mod));
        summary.setModJoinColumns(joinColumnKeys(mod));
        summary.setAggregateChanged(diffAggregateChanged(gen, mod));
        return summary;
    }

    /** 归一化文本 diff 降级 */
    private DiffSummary textDiff(String generatedSql, String modifiedSql) {
        DiffSummary summary = new DiffSummary();
        summary.setAstParsed(false);
        summary.setTextDiffDegraded(true);
        summary.setNormalizedChanged(!normalize(generatedSql).equals(normalize(modifiedSql)));
        return summary;
    }

    private static boolean isSelectFeature(SqlFeature feature) {
        return feature.getStatementClass() != null
                && feature.getStatementClass().contains("READ_ONLY");
    }

    private static List<String> diffTableNames(SqlFeature gen, SqlFeature mod) {
        Set<String> genTables = new LinkedHashSet<>();
        if (gen.getTables() != null) {
            for (TableRef t : gen.getTables()) {
                if (t != null && t.getName() != null && !t.getName().isBlank()) {
                    genTables.add(t.getName().toLowerCase(Locale.ROOT));
                }
            }
        }
        Set<String> modTables = new LinkedHashSet<>();
        if (mod.getTables() != null) {
            for (TableRef t : mod.getTables()) {
                if (t != null && t.getName() != null && !t.getName().isBlank()) {
                    modTables.add(t.getName().toLowerCase(Locale.ROOT));
                }
            }
        }
        return symmetricDiff(genTables, modTables);
    }

    private static List<String> diffColumnKeys(SqlFeature gen, SqlFeature mod) {
        Set<String> genCols = new LinkedHashSet<>();
        collectColumnKeys(gen, genCols);
        Set<String> modCols = new LinkedHashSet<>();
        collectColumnKeys(mod, modCols);
        List<String> changed = symmetricDiff(genCols, modCols);
        return FeedbackSafeText.truncateEach(changed, 64);
    }

    private static void collectColumnKeys(SqlFeature feature, Set<String> target) {
        if (feature.getColumns() == null) {
            return;
        }
        for (ColumnRef c : feature.getColumns()) {
            if (c == null || c.getColumn() == null || c.getColumn().isBlank()) {
                continue;
            }
            String table = c.getTable() == null || c.getTable().isBlank()
                    ? "?" : c.getTable().toLowerCase(Locale.ROOT);
            target.add(table + "." + c.getColumn().toLowerCase(Locale.ROOT));
        }
    }

    /** where 条件中时间相关列差异：仅在 modified 中新增的 where 时间列 → 时间范围错误候选 */
    private static List<String> diffTimeWhereColumns(SqlFeature gen, SqlFeature mod) {
        Set<String> genWhereTime = whereTimeColumns(gen);
        Set<String> modWhereTime = whereTimeColumns(mod);
        List<String> added = new ArrayList<>();
        for (String col : modWhereTime) {
            if (!genWhereTime.contains(col)) {
                added.add(col);
            }
        }
        return FeedbackSafeText.truncateEach(added, 64);
    }

    private static Set<String> whereTimeColumns(SqlFeature feature) {
        Set<String> result = new LinkedHashSet<>();
        if (feature.getColumns() == null) {
            return result;
        }
        for (ColumnRef c : feature.getColumns()) {
            if (c == null || !c.where() || c.getColumn() == null) {
                continue;
            }
            String col = c.getColumn();
            if (TIME_COLUMN_PATTERN.matcher(col).find()) {
                String table = c.getTable() == null || c.getTable().isBlank()
                        ? "?" : c.getTable().toLowerCase(Locale.ROOT);
                result.add(table + "." + col.toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    /** JOIN ON 关联键列集合（table.column 格式，不含 SQL 原文） */
    private static List<String> joinColumnKeys(SqlFeature feature) {
        Set<String> keys = new LinkedHashSet<>();
        if (feature.getColumns() == null) {
            return new ArrayList<>();
        }
        for (ColumnRef c : feature.getColumns()) {
            if (c == null || !c.join() || c.getColumn() == null || c.getColumn().isBlank()) {
                continue;
            }
            String table = c.getTable() == null || c.getTable().isBlank()
                    ? "?" : c.getTable().toLowerCase(Locale.ROOT);
            keys.add(table + "." + c.getColumn().toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(keys);
    }

    /** JOIN ON 关联键列差异：生成 SQL 与修正 SQL 对称差集 */
    private static List<String> diffJoinColumnKeys(SqlFeature gen, SqlFeature mod) {
        return FeedbackSafeText.truncateEach(symmetricDiff(
                new LinkedHashSet<>(joinColumnKeys(gen)),
                new LinkedHashSet<>(joinColumnKeys(mod))), 64);
    }

    private static boolean diffJoinChanged(SqlFeature gen, SqlFeature mod) {
        Integer genCount = gen.getJoinCount();
        Integer modCount = mod.getJoinCount();
        if (genCount != null && modCount != null && !genCount.equals(modCount)) {
            return true;
        }
        Set<String> genTypes = gen.getJoinTypeSet() == null ? Set.of() : gen.getJoinTypeSet();
        Set<String> modTypes = mod.getJoinTypeSet() == null ? Set.of() : mod.getJoinTypeSet();
        return !genTypes.equals(modTypes);
    }

    private static boolean diffAggregateChanged(SqlFeature gen, SqlFeature mod) {
        Set<String> genAgg = new LinkedHashSet<>(gen.getAggregateColumns() == null
                ? List.of() : gen.getAggregateColumns());
        Set<String> modAgg = new LinkedHashSet<>(mod.getAggregateColumns() == null
                ? List.of() : mod.getAggregateColumns());
        if (!genAgg.equals(modAgg)) {
            return true;
        }
        Integer genGroup = gen.getGroupByCount();
        Integer modGroup = mod.getGroupByCount();
        return genGroup != null && modGroup != null && !genGroup.equals(modGroup);
    }

    /** 归一化文本：去空白/小写（供降级比对，不落库不落日志全文） */
    private static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static List<String> symmetricDiff(Set<String> left, Set<String> right) {
        List<String> changed = new ArrayList<>();
        for (String v : left) {
            if (!right.contains(v)) {
                changed.add(v);
            }
        }
        for (String v : right) {
            if (!left.contains(v)) {
                changed.add(v);
            }
        }
        return changed;
    }
}
