package com.agenthub.ai.rcagent.kb.constant;

/**
 * 知识状态机（data-model 数据字典 KnowledgeStatus）。
 *
 * <p>draft → active → needs_review → deprecated；deprecated 即 v0.4 中 inactive（软删除）语义。</p>
 */
public enum KbKnowledgeStatus {

    /** 草稿，不参与正式检索 */
    DRAFT("draft"),
    /** 正式知识，可参与高置信检索 */
    ACTIVE("active"),
    /** 待复核（曾 active，现需人工） */
    NEEDS_REVIEW("needs_review"),
    /** 失效/软删除（=v0.4 inactive） */
    DEPRECATED("deprecated");

    private final String value;

    KbKnowledgeStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (KbKnowledgeStatus s : values()) {
            if (s.value.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
