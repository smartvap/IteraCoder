package com.agenthub.ai.rcagent.crosssource;

import com.agenthub.ai.rcagent.crosssource.InMemoryHashJoiner.JoinOutcome;
import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceQueryResult;
import com.agenthub.ai.rcagent.crosssource.model.SourceQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨源结果合并器（纯内存、无 IO、无状态，module-003）。
 *
 * <p>契约（api-contract §4.9 / FR-BE-030）：</p>
 * <ol>
 *   <li>列名 {@code .trim()} + 剥离 {@code ^[A-Za-z0-9_]+\.} 前缀（**防库名/表名泄露**）；</li>
 *   <li>双侧同名冲突 → 左 {@code l_} / 右 {@code r_} 前缀；仍冲突 → 追加 {@code _2}、{@code _3}…；</li>
 *   <li>值归一：{@code null} → {@code NULL}、String {@code .trim()}、超 60 字符截断（沿用既有口径）；</li>
 *   <li>生成 {@code CROSS_SOURCE_NOTE}（{@code data-model.md §5.9} 版式，截断 note-max-len）；</li>
 *   <li>记录 {@code mergeRulesApplied}（MR-01…MR-06）。</li>
 * </ol>
 *
 * <p>合并结果最终写入**既有** {@code query_result} 键（不新增 MERGED_RESULT），
 * 使既有 {@code rcagent_query_answer} / {@code rcagent_result_check} 零改动消费。</p>
 */
@Slf4j
@Component
public class CrossSourceResultMerger {

    /** 合并后列名冲突时的序号后缀起始值 */
    private static final int DEDUP_SUFFIX_START = 2;

    private final InMemoryHashJoiner joiner;

    public CrossSourceResultMerger(InMemoryHashJoiner joiner) {
        this.joiner = joiner;
    }

    /**
     * 合并（api-contract §4.9 契约签名）：按计划左右源序号定位逐源结果 → 内存 hash join → 合并。
     *
     * @param sources    逐源执行结果（含内存数据行）
     * @param plan       join 计划
     * @param properties 跨源配置
     * @return 合并结果（永不为 null；参数缺失时返回空结果）
     */
    public CrossSourceQueryResult merge(List<SourceQueryResult> sources, CrossSourceJoinPlan plan,
            CrossSourceProperties properties) {
        SourceQueryResult left = findSource(sources, plan == null ? null : plan.getLeftSourceIndex());
        SourceQueryResult right = findSource(sources, plan == null ? null : plan.getRightSourceIndex());
        JoinOutcome joined = joiner.join(left, right, plan, properties);
        boolean pushdownApplied = right != null && right.isPushdownApplied();
        return merge(joined, plan, properties, pushdownApplied);
    }

    /**
     * 合并（执行链路主入口）：消费 join 结果载体，完成列归一 / 冲突消解 / 值归一 / 说明生成。
     *
     * @param joined          内存 join 结果载体
     * @param plan            join 计划
     * @param properties      跨源配置
     * @param pushdownApplied 目标侧本次是否使用了半连接下推 SQL（决定「未下推键过滤」后缀）
     * @return 合并结果（永不为 null）
     */
    public CrossSourceQueryResult merge(JoinOutcome joined, CrossSourceJoinPlan plan,
            CrossSourceProperties properties, boolean pushdownApplied) {
        List<String> leftColumns = normalizeColumns(joined == null ? null : joined.leftColumns());
        List<String> rightColumns = normalizeColumns(joined == null ? null : joined.rightColumns());
        List<Map<String, Object>> sourceRows = joined == null || joined.rows() == null ? List.of() : joined.rows();

        Set<String> collisions = new LinkedHashSet<>(leftColumns);
        collisions.retainAll(new LinkedHashSet<>(rightColumns));

        List<String> finalColumns = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        List<String> rules = new ArrayList<>();
        rules.add(CrossSourceConstants.RULE_COLUMN_NORMALIZE);
        if (!collisions.isEmpty()) {
            rules.add(CrossSourceConstants.RULE_COLUMN_CONFLICT_PREFIX);
        }
        List<String> leftFinal = new ArrayList<>();
        for (String column : leftColumns) {
            String name = collisions.contains(column) ? CrossSourceConstants.COLUMN_PREFIX_LEFT + column : column;
            String unique = dedupe(name, used);
            if (!unique.equals(name)) {
                if (!rules.contains(CrossSourceConstants.RULE_COLUMN_DEDUP_SUFFIX)) {
                    rules.add(CrossSourceConstants.RULE_COLUMN_DEDUP_SUFFIX);
                }
            }
            leftFinal.add(unique);
            finalColumns.add(unique);
        }
        List<String> rightFinal = new ArrayList<>();
        for (String column : rightColumns) {
            String name = collisions.contains(column) ? CrossSourceConstants.COLUMN_PREFIX_RIGHT + column : column;
            String unique = dedupe(name, used);
            if (!unique.equals(name) && !rules.contains(CrossSourceConstants.RULE_COLUMN_DEDUP_SUFFIX)) {
                rules.add(CrossSourceConstants.RULE_COLUMN_DEDUP_SUFFIX);
            }
            rightFinal.add(unique);
            finalColumns.add(unique);
        }

        List<Map<String, Object>> rows = new ArrayList<>(sourceRows.size());
        for (Map<String, Object> sourceRow : sourceRows) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < leftColumns.size(); i++) {
                row.put(leftFinal.get(i), normalizeValue(
                        sourceRow.get(CrossSourceConstants.JOIN_KEY_LEFT + leftColumns.get(i))));
            }
            for (int i = 0; i < rightColumns.size(); i++) {
                row.put(rightFinal.get(i), normalizeValue(
                        sourceRow.get(CrossSourceConstants.JOIN_KEY_RIGHT + rightColumns.get(i))));
            }
            rows.add(row);
        }
        rules.add(CrossSourceConstants.RULE_VALUE_NORMALIZE);
        rules.add(CrossSourceConstants.RULE_JOIN_SEMANTIC);
        boolean truncated = joined != null && joined.truncated();
        if (truncated) {
            rules.add(CrossSourceConstants.RULE_ROW_LIMIT);
        }

        String joinType = plan == null || plan.getJoinType() == null
                ? CrossSourceJoinType.INNER.name() : plan.getJoinType().name();
        String note = buildNote(2, joinType, rows.size(), truncated, pushdownApplied, properties);
        log.info("RCAgent 跨源结果合并: joinType={}, columnCount={}, rowCount={}, truncated={}, pushdownApplied={}",
                joinType, finalColumns.size(), rows.size(), truncated, pushdownApplied);
        return CrossSourceQueryResult.builder()
                .joinType(joinType)
                .sourceCount(2)
                .columnNames(finalColumns)
                .rows(rows)
                .rowCount(rows.size())
                .truncated(truncated)
                .note(note)
                .mergeRulesApplied(rules)
                .build();
    }

    /**
     * 生成 {@code CROSS_SOURCE_NOTE}（执行成功版式，data-model §5.9）：
     * {@code 跨源关联：覆盖 N 个数据源（{JOIN} JOIN）；合并 M 行[（已按上限截断）][；未下推键过滤]}。
     */
    public String buildNote(int sourceCount, String joinType, int rowCount, boolean truncated,
            boolean pushdownApplied, CrossSourceProperties properties) {
        StringBuilder note = new StringBuilder();
        note.append("跨源关联：覆盖 ").append(sourceCount).append(" 个数据源（")
                .append(joinType == null ? CrossSourceJoinType.INNER.name() : joinType)
                .append(" JOIN）；合并 ").append(rowCount).append(" 行");
        if (truncated) {
            note.append(CrossSourceConstants.NOTE_TRUNCATED_SUFFIX);
        }
        if (!pushdownApplied) {
            note.append(CrossSourceConstants.NOTE_NO_PUSHDOWN_SUFFIX);
        }
        int maxLen = properties == null ? 500 : properties.getNoteMaxLen();
        return CrossSourceConstants.truncate(note.toString(), maxLen);
    }

    /** 列名归一：trim + 剥离限定前缀（防库名/表名泄露） */
    private static List<String> normalizeColumns(List<String> columns) {
        List<String> result = new ArrayList<>();
        if (columns == null) {
            return result;
        }
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                continue;
            }
            String bare = CrossSourceConstants.stripQualifier(column);
            result.add(bare == null || bare.isBlank() ? column.trim() : bare);
        }
        return result;
    }

    /** 冲突消解：同名追加 {@code _2}、{@code _3}…（保持顺序，保证列名唯一） */
    private static String dedupe(String name, Set<String> used) {
        if (used.add(name)) {
            return name;
        }
        int suffix = DEDUP_SUFFIX_START;
        String candidate = name + "_" + suffix;
        while (!used.add(candidate)) {
            suffix++;
            candidate = name + "_" + suffix;
        }
        return candidate;
    }

    /** 值归一：null → NULL、String trim、超 60 字符截断（沿用 RcaExecuteNode.displayValue 口径） */
    private static String normalizeValue(Object value) {
        if (value == null) {
            return CrossSourceConstants.NULL_LITERAL;
        }
        String text = value instanceof String string ? string.trim() : String.valueOf(value);
        return text.length() > CrossSourceConstants.CELL_MAX_LEN
                ? text.substring(0, CrossSourceConstants.CELL_MAX_LEN) + "..."
                : text;
    }

    private static SourceQueryResult findSource(List<SourceQueryResult> sources, Integer sourceIndex) {
        if (sources == null || sourceIndex == null) {
            return null;
        }
        for (SourceQueryResult source : sources) {
            if (source != null && sourceIndex.equals(source.getSourceIndex())) {
                return source;
            }
        }
        return null;
    }
}
