package com.agenthub.ai.rcagent.crosssource.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 半连接下推计划（纯内存计算，**不落库**）。字段见 {@code data-model.md §3.6}。
 *
 * <p>由 {@code SemiJoinPushdownPlanner} 产出；{@link #chunks} 内每个字面量均已通过安全白名单
 * （{@code data-model.md §5.3}），可直接拼接进目标侧 {@code WHERE <key> IN (...)}。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushdownPlan {

    /** 下推模式：SINGLE_IN（单条 IN）/ SHARDED_IN（分片 IN）/ NONE（回退不下推） */
    private String pushdownMode;

    /** 目标侧 join 键列名（已剥离限定前缀） */
    private String targetKeyColumn;

    /** 探测侧去重后 key 数 */
    private int keyCount;

    /** 不安全（被剔除）key 数 */
    private int unsafeKeyCount;

    /** 分片字面量列表（每片元素 ≤ semi-join-in-threshold；NONE 时为空） */
    @Builder.Default
    private List<List<String>> chunks = new ArrayList<>();

    /** 片数（SINGLE_IN 为 1；NONE 为 0） */
    private int shardCount;

    /** 备注（如「片数超过上限，回退不下推」） */
    private String note;

    /** 是否可下推（非 NONE 且至少 1 片） */
    public boolean isPushdownApplied() {
        return !"NONE".equals(pushdownMode) && chunks != null && !chunks.isEmpty();
    }
}
