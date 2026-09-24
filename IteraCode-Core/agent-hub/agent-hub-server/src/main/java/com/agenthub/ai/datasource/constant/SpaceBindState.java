package com.agenthub.ai.datasource.constant;

/**
 * llm-wiki 知识空间绑定态（data-model.md SpaceBindState）。
 */
public enum SpaceBindState {

    /** 未启用空间联动（space_name 为空或未请求） */
    NOT_APPLIED,

    /** 已绑定：llm-wiki 空间存在并确认（新建成功或复用同名空间） */
    BOUND,

    /** 待绑定：llm-wiki 不可用降级保存，可手动重试 */
    BIND_PENDING,

    /** 绑定失败：同名冲突未处理或创建失败（用户需选择或重试） */
    BIND_FAILED
}
