package com.agenthub.ai.rcagent.engine.constant;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.Optional;

/**
 * RCAgent 工作流 State 键常量（与 rd 的 RdWorkflowKeys 完全隔离）。
 *
 * <p>所有图节点读写 State 均通过本类常量，禁止魔法字符串散落（FR-BE-003）。
 * check_schema/sql_draft 等 SQL 相关键：草案仅经审核通道可见，日志/SSE 截断。</p>
 */
public final class RcaWorkflowKeys {

    private RcaWorkflowKeys() {
    }

    /** 用户原始问题 */
    public static final String QUESTION = "question";
    /** 绑定的业务数据源 ID（单源场景使用本键；多源场景表示主源） */
    public static final String DS_ID = "ds_id";
    /**
     * 候选源集合（新增，module-002）：有序、去重，元素为 {@code t_ds_config.ds_id}。
     * <p>单源用 {@link #DS_ID}，跨源用本键；恒等式 {@code DS_ID == DS_IDS[0]} 必须成立；
     * 候选集合 ≤1 时退化为 {@code DS_IDS = [DS_ID]}（全链路与现状一致）。
     * 类型 {@code List<String>}，须在 {@code RcaWorkflowGraphConfig#keyStrategyFactory} 注册 ReplaceStrategy。</p>
     */
    public static final String DS_IDS = "ds_ids";
    /**
     * 系统编码（新增，module-002，可空）：所选「系统」的 {@code t_ds_config.system_code}；
     * 单 {@code dsId} 入参且该系统编码非空时填入，否则为 null。消费方见 module-003。
     */
    public static final String SYSTEM_CODE = "system_code";
    /** 意图分类结果：CHAT / QUERY / ATTRIBUTION */
    public static final String INTENT_TYPE = "intent_type";
    /** 意图节点输出（要素解析结果文本） */
    public static final String INTENT_RESULT = "intent_result";
    /** 双库检索统一上下文（证据） */
    public static final String RETRIEVAL_CONTEXT = "retrieval_context";
    /** SQL 规划输出（查询目标拆分） */
    public static final String SQL_PLAN = "sql_plan";
    /** SQL 生成 Agent 输出（SQL 草案） */
    public static final String SQL_GENERATE = "sql_generate";
    /** 验证分级报告文本 */
    public static final String SQL_VALIDATE_REPORT = "sql_validate_report";
    /** 风险等级：BLOCK / LOW / MEDIUM / HIGH */
    public static final String RISK_LEVEL = "risk_level";
    /** 人工审核内容（SQL+归因结论摘要） */
    public static final String REVIEW_CONTENT = "review_content";
    /** 审核决策：APPROVED / SENT_BACK / TERMINATED */
    public static final String REVIEW_DECISION = "review_decision";
    /** 审核意见/打回备注 */
    public static final String REVIEW_FEEDBACK = "review_feedback";
    /** 只读执行结果（序列化文本） */
    public static final String QUERY_RESULT = "query_result";
    /** 归因分析结论 */
    public static final String ATTRIBUTION_RESULT = "attribution_result";
    /** CHAT 意图的 LLM 答题结果（聚焦、简洁的自然语言回答；LLM 失败时该键不写入） */
    public static final String ANSWER = "answer";
    /** QUERY 意图的查数结论（自然语言，单句）；LLM 失败时该键不写入 */
    public static final String QUERY_ANSWER = "query_answer";
    /** 结果摘要（展示给用户） */
    public static final String RESULT_SUMMARY = "result_summary";
    /** 工作流状态 */
    public static final String WORKFLOW_STATUS = "workflow_status";
    /** 工作流消息横幅 */
    public static final String WORKFLOW_MESSAGE = "workflow_message";
    /** StateGraph 线程 ID 键（MultiRoundAgentNode/SSE 复用） */
    public static final String THREAD_ID_KEY = "_thread_id_";

    // ==================== 跨源 join 键（module-003 cross-source-join，新增） ====================

    /**
     * 用户显式指定跨源 join（新增，module-003）：结构化文本
     * {@code joinType=..;leftDsId=..;rightDsId=..;leftJoinKey=..;rightJoinKey=..}（仅拼接非空项）。
     * <p><b>可见性 INTERNAL</b>：不进 SSE、{@code /state} 为脱敏占位；由 {@code start} 兼容扩展字段写入，
     * 无任何跨源字段时**不写该键**（保证单源零回归）。</p>
     */
    public static final String JOIN_HINT = "join_hint";
    /**
     * 结构化 join 计划（新增，module-003）：JSON 文本，截断 plan-max-len。
     * <p><b>可见性 INTERNAL（审核通道可见）</b>：不进 SSE、{@code /state} 为脱敏占位；
     * 空/缺失 = 已降级单源（条件边走 degrade → 既有单源链路）。</p>
     */
    public static final String JOIN_PLAN = "join_plan";
    /**
     * 逐源 SQL 映射（新增，module-003）：JSON 数组（{@code SourceQuerySql} 列表）。
     * <p><b>可见性 INTERNAL（审核通道可见）</b>：SQL 原文仅审核通道 / 逐源执行可见，
     * **禁止进 SSE**、{@code /state} 为脱敏占位。</p>
     */
    public static final String SOURCE_QUERY_PLANS = "source_query_plans";
    /**
     * 逐源执行摘要（新增，module-003）：JSON 数组（行数/列名/耗时/截断/下推信息，**不含数据行**）。
     * <p><b>可见性 INTERNAL</b>：不进 SSE、{@code /state} 为脱敏占位。</p>
     */
    public static final String SOURCE_QUERY_RESULTS = "source_query_results";
    /**
     * 跨源说明（新增，module-003）：脱敏文本（覆盖 N 个源 / join 类型 / 是否截断），截断 note-max-len。
     * <p><b>可见性 EXTERNAL（脱敏）</b>：进 SSE、{@code /state} 原文返回、前端元信息区展示；
     * 禁止含 ds_id / 库名 / 表名 / SQL 原文 / 连接串。</p>
     */
    public static final String CROSS_SOURCE_NOTE = "cross_source_note";


    /**
     * 从 OverAllState 中安全提取文本值。State 中的 Agent 输出可能存储为
     * AssistantMessage / GraphResponse&lt;AssistantMessage&gt; 等非 String 类型，
     * 直接 toString() 会得到包装对象。
     */
    public static String extractStateText(OverAllState state, String key) {
        Optional<Object> opt = state.value(key);
        if (opt.isEmpty()) return "";
        Object v = opt.get();
        if (v instanceof AssistantMessage msg) {
            return msg.getText();
        }
        if (v instanceof Message msg) {
            return msg.getText();
        }
        // GraphResponse（asNode 直接输出时存为 GraphResponse<AssistantMessage>）
        try {
            java.lang.reflect.Method m = v.getClass().getMethod("resultValue");
            Object innerOpt = m.invoke(v);
            if (innerOpt instanceof Optional<?> o && o.isPresent()) {
                Object inner = o.get();
                if (inner instanceof AssistantMessage msg) return msg.getText();
                if (inner instanceof Message msg) return msg.getText();
                return inner.toString();
            }
        } catch (Exception ignored) {}
        return v.toString();
    }

    /**
     * 从 OverAllState 中安全提取文本值，取不到时返回默认值。
     */
    public static String extractStateText(OverAllState state, String key, String defaultValue) {
        String val = extractStateText(state, key);
        return val.isEmpty() ? defaultValue : val;
    }
}
