package com.agenthub.ai.dbaccess.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 查询执行结果（data-model.md t_query_result，Executor 返回，不落库）。
 *
 * <p>行数 ≤ maxRows；禁止携带连接/账号信息。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryResult {

    /** 数据源标识 */
    private String dsId;

    /** 列名 */
    private List<String> columnNames;

    /** 结果行（JSON 数组，最多 maxRows 行；列有序，值为 JDBC 基本类型/String） */
    private List<Map<String, Object>> rows;

    /** 实际返回行数（≤ maxRows） */
    private Integer rowCount;

    /** 是否因 maxRows 截断 */
    private Boolean truncated;

    /** 执行耗时 ms */
    private Long costMs;

    /** 本次是否发生敏感字段脱敏 */
    private Boolean sensitiveMasked;

    /** explainOnly=true 时的执行计划文本/JSON */
    private String explainPlan;

    public static QueryResult of(String dsId) {
        return QueryResult.builder()
                .dsId(dsId)
                .columnNames(new ArrayList<>())
                .rows(new ArrayList<>())
                .rowCount(0)
                .truncated(false)
                .costMs(0L)
                .sensitiveMasked(false)
                .build();
    }
}
