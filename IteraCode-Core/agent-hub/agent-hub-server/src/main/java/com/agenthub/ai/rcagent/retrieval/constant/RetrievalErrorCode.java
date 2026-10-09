package com.agenthub.ai.rcagent.retrieval.constant;

/**
 * retrieval 模块业务错误码（42101~42104）。
 *
 * <p>按 api-contract.md 定义，不改存量 base.common.ErrorCode 枚举；
 * 使用方式：{@code throw new BusinessException(RetrievalErrorCode.DS_ID_MISSING, "ds_id 不能为空")}。</p>
 */
public final class RetrievalErrorCode {

    private RetrievalErrorCode() {
    }

    /** ds_id 缺失或未路由（BR-001 显式路由） */
    public static final int DS_ID_MISSING = 42101;

    /** 检索参数非法（question/query_elements/top_k/filters 非法） */
    public static final int PARAM_ILLEGAL = 42102;

    /** 知识库 A 内部检索失败（调用 module-004 kb 检索服务/接口失败或超时） */
    public static final int KB_A_FAILED = 42103;

    /** Rerank 服务不可用（显式启用 Rerank 但 bge-reranker 不可达，P2） */
    public static final int RERANK_UNAVAILABLE = 42104;
}
