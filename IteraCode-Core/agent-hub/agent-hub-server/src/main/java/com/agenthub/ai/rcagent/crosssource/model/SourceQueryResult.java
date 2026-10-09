package com.agenthub.ai.rcagent.crosssource.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 逐源执行摘要（内存模型，State 内部键 {@code source_query_results}，**不落库**）。
 *
 * <p>字段见 {@code data-model.md §3.4}。⚠️ 关键约束：{@link #dataRows} 为**执行阶段 JVM 内存数据行**，
 * 严禁写入 State / checkpoint（序列化时通过 {@link JSONField}(serialize = false) 排除），
 * 仅 {@link #rowCount}/{@link #columns}/耗时/截断/下推信息进入 State 摘要。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceQueryResult {

    /** 源序号 */
    private Integer sourceIndex;

    /** 源 dsId（内部） */
    private String dsId;

    /** 执行状态：SUCCESS / FAILED / TIMEOUT / REJECTED / BLOCK_SQL / SQL_NOT_READONLY / SQL_EMPTY */
    private String execState;

    /** 实际行数（≤ 行数上限） */
    private Integer rowCount;

    /** 是否因行数上限截断 */
    private boolean truncated;

    /** 耗时（毫秒） */
    private Long costMs;

    /** 列名清单（写入 State 前已剥离库/表前缀，防库表名泄露） */
    @Builder.Default
    private List<String> columns = new ArrayList<>();

    /** 本次执行是否使用半连接下推 SQL */
    private boolean pushdownApplied;

    /** 本次执行的分片数（0/1/N） */
    private int pushdownShards;

    /** 因不符合下推安全白名单被剔除的 key 数 */
    private int unsafeKeyCount;

    /** 脱敏失败原因（不含 dsId / SQL / 库名 / 连接串） */
    private String failureReason;

    /**
     * 数据行（仅 JVM 内存，**不入 State**）：键为 join 结果内部键
     * （{@code L::列名} / {@code R::列名}），由 {@code InMemoryHashJoiner} 产出。
     */
    @JSONField(serialize = false)
    @Builder.Default
    private List<Map<String, Object>> dataRows = new ArrayList<>();

    /** 该源是否执行成功 */
    public boolean isSuccess() {
        return "SUCCESS".equals(execState);
    }
}
