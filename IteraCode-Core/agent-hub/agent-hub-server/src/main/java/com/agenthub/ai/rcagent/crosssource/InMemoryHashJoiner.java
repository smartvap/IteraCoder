package com.agenthub.ai.rcagent.crosssource;

import com.agenthub.ai.rcagent.crosssource.config.CrossSourceProperties;
import com.agenthub.ai.rcagent.crosssource.model.CrossSourceJoinPlan;
import com.agenthub.ai.rcagent.crosssource.model.SourceQueryResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 内存 hash join（INNER / LEFT，纯内存、无 IO、无状态，module-003）。
 *
 * <p>契约（api-contract §4.9）：① 右侧按 join key 归一化建 hash 分桶；② INNER 仅匹配行；
 * LEFT 左全保留、未匹配右列补 {@code NULL}；③ 空值 key **不参与匹配**（INNER 中空键左侧行被丢弃，
 * LEFT 中空键左侧行保留且右侧列全 {@code NULL}）；④ 累积到 {@code merged-row-limit} 即停并置
 * {@code truncated=true}（防 OOM）；⑤ 多对多同样受上限提前终止。</p>
 *
 * <p>输出 {@link JoinOutcome} 为**中间载体**（键带 {@code L::} / {@code R::} 侧标记，
 * 避免同名列表格互相覆盖），最终列名归一 / 冲突消解 / 值归一 / 版式由
 * {@code CrossSourceResultMerger} 完成。</p>
 */
@Slf4j
@Component
public class InMemoryHashJoiner {

    /**
     * 执行内存 hash join。
     *
     * @param left       左源执行结果（含内存数据行）
     * @param right      右源执行结果（含内存数据行）
     * @param plan       join 计划（提供 join 类型与两侧 key）
     * @param properties 跨源配置（取 merged-row-limit / join-key-ignore-case）
     * @return join 结果载体（永不为 null）
     */
    public JoinOutcome join(SourceQueryResult left, SourceQueryResult right,
            CrossSourceJoinPlan plan, CrossSourceProperties properties) {
        List<String> leftColumns = resolveColumns(left);
        List<String> rightColumns = resolveColumns(right);
        List<Map<String, Object>> leftRows = left == null || left.getDataRows() == null
                ? List.of() : left.getDataRows();
        List<Map<String, Object>> rightRows = right == null || right.getDataRows() == null
                ? List.of() : right.getDataRows();
        boolean leftJoin = plan != null && plan.getJoinType() == CrossSourceJoinType.LEFT;
        String leftKey = plan == null ? null : plan.getLeftKey();
        String rightKey = plan == null ? null : plan.getRightKey();
        int rowLimit = properties == null ? 2000 : Math.max(1, properties.getMergedRowLimit());

        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> rightRow : rightRows) {
            String token = SemiJoinPushdownPlanner.normalizeKeyToken(
                    keyValue(rightRow, rightKey, rightColumns), properties);
            if (token == null) {
                continue;
            }
            buckets.computeIfAbsent(token, k -> new ArrayList<>()).add(rightRow);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        outer:
        for (Map<String, Object> leftRow : leftRows) {
            String token = SemiJoinPushdownPlanner.normalizeKeyToken(
                    keyValue(leftRow, leftKey, leftColumns), properties);
            List<Map<String, Object>> matches = token == null ? null : buckets.get(token);
            if (matches == null || matches.isEmpty()) {
                if (!leftJoin) {
                    continue;
                }
                if (rows.size() >= rowLimit) {
                    truncated = true;
                    break;
                }
                rows.add(buildRow(leftRow, null, leftColumns, rightColumns));
                continue;
            }
            for (Map<String, Object> rightRow : matches) {
                if (rows.size() >= rowLimit) {
                    truncated = true;
                    break outer;
                }
                rows.add(buildRow(leftRow, rightRow, leftColumns, rightColumns));
            }
        }
        log.info("RCAgent 内存 hash join: joinType={}, leftRows={}, rightRows={}, mergedRows={}, truncated={}",
                leftJoin ? CrossSourceJoinType.LEFT.name() : CrossSourceJoinType.INNER.name(),
                leftRows.size(), rightRows.size(), rows.size(), truncated);
        return new JoinOutcome(leftColumns, rightColumns, rows, truncated);
    }

    /** 组装单行（两侧列分别带 {@code L::} / {@code R::} 侧标记，避免同名互相覆盖） */    private static Map<String, Object> buildRow(Map<String, Object> leftRow, Map<String, Object> rightRow,
            List<String> leftColumns, List<String> rightColumns) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : leftColumns) {
            row.put(CrossSourceConstants.JOIN_KEY_LEFT + column, valueOf(leftRow, column));
        }
        for (String column : rightColumns) {
            row.put(CrossSourceConstants.JOIN_KEY_RIGHT + column, valueOf(rightRow, column));
        }
        return row;
    }

    /**
     * 提取数据行中的 join key 值集合（探测侧 key 集合 → 半连接下推字面量来源）。
     * 空值/缺失值以 {@code null} 占位（由下推规划器按白名单剔除并计数）。
     *
     * @param rows    数据行（可空）
     * @param keyName join 键列名（声明形态，可含限定前缀）
     * @return key 值列表（顺序与行顺序一致；{@code rows} 为空返回空列表）
     */
    public static List<Object> extractKeyValues(List<Map<String, Object>> rows, String keyName) {
        List<Object> values = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return values;
        }
        for (Map<String, Object> row : rows) {
            values.add(keyValue(row, keyName, List.of()));
        }
        return values;
    }

    /** 列清单解析：优先取声明列（保持顺序），缺失时回落首行键集合 */
    private static List<String> resolveColumns(SourceQueryResult result) {
        Set<String> columns = new LinkedHashSet<>();
        if (result != null && result.getColumns() != null) {
            for (String column : result.getColumns()) {
                if (column != null && !column.isBlank()) {
                    columns.add(column.trim());
                }
            }
        }
        if (columns.isEmpty() && result != null && result.getDataRows() != null) {
            for (Map<String, Object> row : result.getDataRows()) {
                if (row == null) {
                    continue;
                }
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (entry.getKey() != null && !entry.getKey().isBlank()) {
                        columns.add(entry.getKey());
                    }
                }
                break;
            }
        }
        return new ArrayList<>(columns);
    }

    /** 行内取 join key 值（精确 → 大小写不敏感 → 剥离限定前缀大小写不敏感） */
    private static Object keyValue(Map<String, Object> row, String keyName, List<String> columns) {
        if (keyName == null || keyName.isBlank()) {
            return null;
        }
        Object value = valueOf(row, keyName);
        if (value != null) {
            return value;
        }
        String bare = CrossSourceConstants.stripQualifier(keyName);
        if (bare != null && !bare.equals(keyName)) {
            value = valueOf(row, bare);
            if (value != null) {
                return value;
            }
        }
        if (row != null) {
            String lower = keyName.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(lower)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /** 行内取值（精确 → 大小写不敏感 → 剥离限定前缀后大小写不敏感） */
    private static Object valueOf(Map<String, Object> row, String column) {
        if (row == null || column == null) {
            return null;
        }
        if (row.containsKey(column)) {
            return row.get(column);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        String bare = CrossSourceConstants.stripQualifier(column);
        if (bare != null && !bare.isBlank()) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    continue;
                }
                String bareKey = CrossSourceConstants.stripQualifier(key);
                if (bareKey != null && bareKey.equalsIgnoreCase(bare)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /**
     * join 结果载体（不可变，仅 JVM 内存）。
     *
     * @param leftColumns  左源列名（保持结果集顺序）
     * @param rightColumns 右源列名
     * @param rows         合并行（键带 {@code L::} / {@code R::} 侧标记）
     * @param truncated    是否因 merged-row-limit 提前终止
     */
    public record JoinOutcome(List<String> leftColumns, List<String> rightColumns,
                              List<Map<String, Object>> rows, boolean truncated) {

        /** 合并行数 */
        public int rowCount() {
            return rows == null ? 0 : rows.size();
        }
    }
}
