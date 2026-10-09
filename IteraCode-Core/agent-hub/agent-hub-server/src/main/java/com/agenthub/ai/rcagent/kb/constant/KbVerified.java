package com.agenthub.ai.rcagent.kb.constant;

/**
 * 已验证标记（data-model 数据字典 Verified）。
 *
 * <p>verified=1 且 status=active 才参与高置信检索（BR-001）。</p>
 */
public enum KbVerified {

    /** 未验证（不进高置信检索） */
    NO(0),
    /** 已验证（可进高置信检索，仍需 status=active） */
    YES(1);

    private final int value;

    KbVerified(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
