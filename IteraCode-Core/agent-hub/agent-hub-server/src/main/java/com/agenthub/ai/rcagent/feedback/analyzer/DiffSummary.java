package com.agenthub.ai.rcagent.feedback.analyzer;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AST diff 摘要（db fb_feedback_flow.diff_summary JSON 列对象）。
 *
 * <p>仅保存确定性差异点（表/列/where 时间/join/聚合变化），<b>不含全量 SQL 原文</b>
 * （data-model 注意事项 7 / AGENTS.md 5.4）。字段为摘要/布尔，可被 fastjson2
 * 序列化为 JSON 落库、反序列化后返回 VO。</p>
 */
@Data
public class DiffSummary {

    /** 是否成功解析为 AST（false=module-006 未就绪/语法不支持，已降级文本 diff） */
    private boolean astParsed;

    /** 文本归一化 diff 降级标记 */
    private boolean textDiffDegraded;

    /** 表集合变化（表名列表，不含 SQL 原文） */
    private List<String> changedTables = new ArrayList<>();

    /** 列集合变化（table.column 格式，不含 SQL 原文） */
    private List<String> changedColumns = new ArrayList<>();

    /** where 条件中新增/变化的时间相关列（时间范围错误判据） */
    private List<String> addedWhere = new ArrayList<>();

    /** JOIN 数量/类型变化 */
    private boolean joinChanged;

    /** JOIN ON 关联键列集合变化（table.column，修正 SQL 相对生成 SQL 新增/删除的关联键） */
    private List<String> changedJoinColumns = new ArrayList<>();

    /** 修正 SQL（modifiedSql）JOIN ON 关联键列（table.column，用于 RELATION 建议还原关联表对） */
    private List<String> modJoinColumns = new ArrayList<>();

    /** 聚合函数/分组变化 */
    private boolean aggregateChanged;

    /** 归一化文本存在差异（降级判定） */
    private boolean normalizedChanged;
}
