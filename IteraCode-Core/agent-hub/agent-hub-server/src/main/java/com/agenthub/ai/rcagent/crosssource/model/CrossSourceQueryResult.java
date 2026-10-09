package com.agenthub.ai.rcagent.crosssource.model;

import com.agenthub.ai.rcagent.crosssource.CrossSourceConstants;
import com.agenthub.ai.rcagent.engine.node.RcaNodeUtils;
import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 跨源合并结果（内存模型，序列化后写入**既有** {@code query_result} 键，**不落库**）。
 *
 * <p>字段见 {@code data-model.md §3.5}；{@link #toDisplayText()} 逐字符版式见 api-contract §4.5.2。
 * 该文本同时被既有 {@code rcagent_query_answer} → {@code rcagent_result_check} 零改动消费。</p>
 *
 * <p><b>安全</b>：表头与明细**不得**出现 ds_id、库名、表名、SQL 原文、连接串、账号、凭据
 * （列名已剥离库/表前缀，值已归一截断）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrossSourceQueryResult {

    /** join 类型：INNER / LEFT */
    private String joinType;

    /** 覆盖源数量（对外仅暴露数量） */
    private int sourceCount;

    /** 合并列名（已归一 + 冲突消解，不含库/表前缀） */
    @Builder.Default
    private List<String> columnNames = new ArrayList<>();

    /** 合并行（JVM 内存；序列化时仅取前 N 行文本，N ≤ 30） */
    @JSONField(serialize = false)
    @Builder.Default
    private List<Map<String, Object>> rows = new ArrayList<>();

    /** 合并后行数（截断前真实行数） */
    private int rowCount;

    /** 是否因 merged-row-limit 截断 */
    private boolean truncated;

    /** 脱敏跨源说明（= CROSS_SOURCE_NOTE，data-model §5.9 版式） */
    private String note;

    /** 已应用的合并规则编码（MR-01…MR-06） */
    @Builder.Default
    private List<String> mergeRulesApplied = new ArrayList<>();

    /**
     * 合并结果展示文本（api-contract §4.5.2 逐字符版式），整体截断 {@code 8000}。
     *
     * <pre>
     * ===== 跨源关联结果 =====
     * 覆盖数据源：2（INNER JOIN）
     * 行数：37（已截断：否）
     * 列：l_order_id, l_customer_id, r_customer_name
     * | l_order_id=1001 ; l_customer_id=C01 ; r_customer_name=张三 ; |
     * ... 共 37 行
     * （说明：本次查询覆盖多个数据源，明细为按关联键合并后的结果）
     * </pre>
     */
    public String toDisplayText() {
        StringBuilder sb = new StringBuilder();
        sb.append(CrossSourceConstants.MERGE_HEADER).append('\n');
        sb.append(CrossSourceConstants.COVER_SOURCE_PREFIX).append(sourceCount)
                .append("（").append(displayJoinType()).append("）\n");
        sb.append(CrossSourceConstants.ROW_COUNT_PREFIX).append(rowCount)
                .append(truncated ? CrossSourceConstants.TRUNCATED_YES : CrossSourceConstants.TRUNCATED_NO)
                .append('\n');
        if (columnNames != null && !columnNames.isEmpty()) {
            sb.append(CrossSourceConstants.COLUMN_PREFIX)
                    .append(String.join(", ", columnNames)).append('\n');
        }
        List<Map<String, Object>> safeRows = rows == null ? List.of() : rows;
        int shown = 0;
        for (Map<String, Object> row : safeRows) {
            if (shown >= CrossSourceConstants.DISPLAY_ROW_LIMIT) {
                break;
            }
            sb.append(CrossSourceConstants.ROW_LINE_PREFIX);
            if (columnNames != null) {
                for (String column : columnNames) {
                    Object value = row == null ? null : row.get(column);
                    sb.append(column).append('=').append(displayValue(value))
                            .append(CrossSourceConstants.CELL_SEPARATOR);
                }
            }
            sb.append("|\n");
            shown++;
        }
        if (safeRows.size() > shown) {
            sb.append(CrossSourceConstants.REMAIN_ROW_PREFIX).append(safeRows.size())
                    .append(CrossSourceConstants.REMAIN_ROW_SUFFIX).append('\n');
        }
        sb.append(CrossSourceConstants.MERGE_FOOTER);
        return RcaNodeUtils.truncate(sb.toString(), CrossSourceConstants.QUERY_RESULT_MAX_LEN);
    }

    /** join 类型展示（INNER JOIN / LEFT JOIN；缺省按 INNER） */
    private String displayJoinType() {
        if (joinType == null || joinType.isBlank()) {
            return "INNER JOIN";
        }
        return joinType.trim().toUpperCase() + " JOIN";
    }

    /** 明细值渲染：null → NULL；其余取字符串（已在上游归一截断） */
    private static String displayValue(Object value) {
        return value == null ? CrossSourceConstants.NULL_LITERAL : String.valueOf(value);
    }
}
