package com.agenthub.ai.dbaccess.provider;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 有状态 ResultSet 桩：按行数据模拟真实 JDBC 驱动的 {@code getXxx/wasNull} 语义。
 *
 * <p>为什么需要「有状态」桩：{@code rs.wasNull()} 的语义是「最近一次列读取是否为 SQL NULL」，
 * 而 Mockito 默认对所有方法返回 0/null/false（{@code wasNull()} 恒为 false），会把 NULL 静默折叠成 0，
 * 使「NULL 严格为 null（非 0）」这一关键断言语义失真。本桩在每次 {@code getInt/getLong/getString}
 * 读取后更新「最后一次读取是否为 NULL」，{@code wasNull()} 返回该状态，从而对被测代码中的
 * {@code getInt(...) + wasNull()} 组合给出与真实驱动一致的结果。</p>
 *
 * <p><b>注意</b>：Mockito 驱动下 {@code getInt(label)} 未命中行数据（label 缺省）按 NULL 处理
 * （返回 0 且 {@code wasNull()=true}），与真实驱动「label 不存在即抛异常」不同；因此本桩仅适用于
 * 被测代码已明确读取的列标签。</p>
 */
final class ResultSetMocks {

    private ResultSetMocks() {
    }

    /**
     * 构造按行迭代的 ResultSet 桩。调用方需保证被测代码只读取行数据中出现过的列标签。
     */
    static ResultSet rows(List<Map<String, Object>> rows) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int[] index = {-1};
        boolean[] lastNull = {false};

        when(rs.next()).thenAnswer(invocation -> {
            index[0]++;
            return index[0] < rows.size();
        });
        when(rs.wasNull()).thenAnswer(invocation -> lastNull[0]);
        when(rs.getInt(anyString())).thenAnswer(invocation -> {
            Object value = read(rows, index[0], invocation.getArgument(0), lastNull);
            return value == null ? 0 : ((Number) value).intValue();
        });
        when(rs.getLong(anyString())).thenAnswer(invocation -> {
            Object value = read(rows, index[0], invocation.getArgument(0), lastNull);
            return value == null ? 0L : ((Number) value).longValue();
        });
        when(rs.getString(anyString())).thenAnswer(invocation -> {
            Object value = read(rows, index[0], invocation.getArgument(0), lastNull);
            return value == null ? null : String.valueOf(value);
        });
        return rs;
    }

    /** 构造一行列数据：{@code row("COLUMN_NAME", "ID", "DATA_TYPE", "NUMBER")} */
    static Map<String, Object> row(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("row(...) 必须为 key/value 成对参数");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static Object read(List<Map<String, Object>> rows, int index, String label, boolean[] lastNull) {
        if (index < 0 || index >= rows.size()) {
            throw new IllegalStateException("ResultSet 尚未 next() 或已越界：row=" + index);
        }
        Object value = rows.get(index).get(label);
        lastNull[0] = (value == null);
        return value;
    }
}
