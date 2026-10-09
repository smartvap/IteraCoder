package com.agenthub.ai.rcagent.engine.node;

/**
 * RCAgent 图节点通用工具（纯逻辑）。
 */
public final class RcaNodeUtils {

    private RcaNodeUtils() {
    }

    /** 截断长文本（用于日志/SSE/响应等展示，避免敏感与超长内容全量外泄，AGENTS.md 5.3） */
    public static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...[已截断]";
    }
}
