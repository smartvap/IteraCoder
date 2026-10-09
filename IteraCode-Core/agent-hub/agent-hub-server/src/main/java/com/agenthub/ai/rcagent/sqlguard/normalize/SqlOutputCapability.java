package com.agenthub.ai.rcagent.sqlguard.normalize;

/**
 * RCAgent L1 SQL 输出能力探测结果（进程内按模型标识缓存）。
 *
 * <p>降级链：{@link #STRUCTURED_JSON_SCHEMA} → 失败 → {@link #TOOL_CALLING} → 失败 → {@link #TEXT_ONLY}。
 * <b>安全默认恒为 {@link #TEXT_ONLY}</b>：探测失败 / 未探测 / 异常一律降级为纯文本链路，
 * 不得因结构化输出不可用而使生成节点失败（spec.md A.6 P1）。</p>
 */
public enum SqlOutputCapability {

    /** 模型 / 网关支持 json_schema 或 Spring AI 结构化输出（{@code ChatClient.entity}） */
    STRUCTURED_JSON_SCHEMA,

    /** 模型支持 function / tool calling（以「提交 SQL 草案」工具名请求结构化参数） */
    TOOL_CALLING,

    /** 不支持上述任一，或探测失败：降级为纯文本请求 + L2 全分层提取 */
    TEXT_ONLY;

    /**
     * 安全默认能力（探测失败 / 未探测 / 异常统一返回）。
     */
    public static SqlOutputCapability safeDefault() {
        return TEXT_ONLY;
    }
}
