package com.agenthub.ai.rcagent.feedback.constant;

/**
 * B 库（llm-wiki）回写出站任务状态（fb_wiki_writeback.status）。
 */
public enum WikiWritebackStatus {

    /** 待执行/等待重试 */
    PENDING("PENDING"),
    /** 正在写 llm-wiki（createPage/updatePage + syncPages） */
    RUNNING("RUNNING"),
    /** 页面写入 + sync 发起成功（远端任务完成轮询后置 SUCCESS） */
    SUCCESS("SUCCESS"),
    /** 重试超限失败（告警/转人工） */
    FAILED("FAILED"),
    /** 反馈被拒/关闭后取消 */
    CANCELED("CANCELED");

    private final String value;

    WikiWritebackStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static boolean isValid(String v) {
        if (v == null || v.isBlank()) {
            return false;
        }
        for (WikiWritebackStatus s : values()) {
            if (s.value.equals(v)) {
                return true;
            }
        }
        return false;
    }

    public static WikiWritebackStatus from(String v) {
        if (v == null) {
            return null;
        }
        for (WikiWritebackStatus s : values()) {
            if (s.value.equals(v)) {
                return s;
            }
        }
        return null;
    }
}
