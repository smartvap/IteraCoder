package com.agenthub.ai.rcagent.engine.dto;

import lombok.Data;

import java.util.List;

/**
 * 启动 RCAgent 会话入参（POST /api/v1/rcagent/start）。
 *
 * <p>多源入口：{@code dsIds}（显式子集）&gt; {@code systemCode}（按系统展开）&gt; {@code dsId}（单源，向后兼容）；
 * 三者至少一个非空，全缺省 → 40000「请先选择数据源或系统」。</p>
 *
 * <p>跨源 join 兜底入口（module-003 兼容扩展，全部**可选**）：{@code joinType} / {@code leftDsId} /
 * {@code rightDsId} / {@code leftJoinKey} / {@code rightJoinKey}；不传时行为与 module-002 交付态
 * **逐字符一致**（不写 {@code join_hint}）。任一非空时要求候选源恰为 2 个，校验失败 40000。</p>
 */
@Data
public class RcaWorkflowStartRequest {

    /** 用户原始问题（必填，≤4000） */
    private String question;

    /** 绑定的业务数据源 ID（可空，≤64，单源/主源，BR-001 显式绑定） */
    private String dsId;

    /** 系统编码（可空，≤64）：按 t_ds_config.system_code 全量展开候选源 */
    private String systemCode;

    /** 显式候选源子集（可空，每项 ≤64，须同属一个系统，≤上限）：优先级最高 */
    private List<String> dsIds;

    /** 跨源 join 类型（可空，INNER / LEFT，大小写不敏感）；空则由 LLM 计划决定 */
    private String joinType;

    /** 跨源 join 左源 dsId（可空，≤64，必须 ∈ 候选源集合）；空则由 LLM 计划决定 */
    private String leftDsId;

    /** 跨源 join 右源 dsId（可空，≤64，必须 ∈ 候选源集合且 ≠ 左源） */
    private String rightDsId;

    /** 跨源 join 左源关联键（可空，≤128，仅字母/数字/下划线/点号）；与 rightJoinKey 成对出现 */
    private String leftJoinKey;

    /** 跨源 join 右源关联键（可空，规则同 leftJoinKey） */
    private String rightJoinKey;
}
