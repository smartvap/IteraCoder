package com.agenthub.ai.rcagent.crosssource.model;

import com.agenthub.ai.rcagent.crosssource.CrossSourceJoinType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨源 join 计划（内存模型，逻辑表 {@code t_cross_source_join_plan}，**不落库**）。
 *
 * <p>字段逐条对照 {@code data-model.md §3.1}；序列化后写入 State 内部键 {@code join_plan}
 * （截断 {@code plan-max-len}），仅审核通道可见（不进 SSE、{@code /state} 为脱敏占位）。</p>
 *
 * <p><b>内部字段</b>：{@link #leftDsId} / {@link #rightDsId} / {@link #leftTable} / {@link #rightTable}
 * 及过滤/聚合文本禁止出现在对外内容（CROSS_SOURCE_NOTE / 合并结果 / SSE）中。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CrossSourceJoinPlan {

    /** 会话线程 ID（State 键 {@code _thread_id_}） */
    private String threadId;

    /** join 类型：INNER / LEFT */
    private CrossSourceJoinType joinType;

    /** 左源序号（1 或 2） */
    private Integer leftSourceIndex;

    /** 右源序号 */
    private Integer rightSourceIndex;

    /** 左源 dsId（内部字段，禁止外显） */
    private String leftDsId;

    /** 右源 dsId（内部字段，禁止外显） */
    private String rightDsId;

    /** 左源 join 键（列名） */
    private String leftKey;

    /** 右源 join 键（列名） */
    private String rightKey;

    /** 参与源数量（恒为 2；&gt; max-sources 明确拒绝） */
    private Integer sourceCount;

    /** 左源目标表（内部） */
    private String leftTable;

    /** 右源目标表（内部） */
    private String rightTable;

    /** 左源投影列（语义化别名） */
    @Builder.Default
    private List<String> leftProjections = new ArrayList<>();

    /** 右源投影列 */
    @Builder.Default
    private List<String> rightProjections = new ArrayList<>();

    /** 左源 join 前过滤条件（下推） */
    @Builder.Default
    private List<String> leftFilters = new ArrayList<>();

    /** 右源 join 前过滤条件（下推） */
    @Builder.Default
    private List<String> rightFilters = new ArrayList<>();

    /** 左源聚合（可选下推） */
    private String leftAggregation;

    /** 右源聚合（可选下推） */
    private String rightAggregation;

    /** join 后聚合说明（合并结果上计算） */
    private String postJoinAggregation;

    /** join key 字符串比较是否忽略大小写（默认仅 trim） */
    private boolean keyIgnoreCase;

    /** 计划来源：LLM / USER_OVERRIDE */
    private String planSource;

    /** 降级原因（非空表示不进入跨源执行；取值见 CrossSourceConstants.DEGRADE_*） */
    private String degradeReason;

    /** LLM 原始输出（截断 plan-max-len；内部/审核通道） */
    private String rawPlanText;

    /**
     * 计划是否可执行：无降级原因 + join 类型合法 + 两侧 join key 非空
     * （对齐 api-contract §4.1 {@code isExecutable()}）。
     */
    public boolean isExecutable() {
        return degradeReason == null
                && joinType != null
                && leftKey != null && !leftKey.isBlank()
                && rightKey != null && !rightKey.isBlank();
    }

    /** 是否为用户显式指定计划（JOIN_HINT 兜底入口） */
    public boolean isUserOverride() {
        return com.agenthub.ai.rcagent.crosssource.CrossSourceConstants.PLAN_SOURCE_USER_OVERRIDE
                .equals(planSource);
    }
}
