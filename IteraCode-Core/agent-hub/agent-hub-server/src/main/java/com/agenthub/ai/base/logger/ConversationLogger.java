package com.agenthub.ai.base.logger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对话日志记录器 — AI 对话专用日志
 * @since 2026-07-29 — AI 对话专用日志
 * 输出到 logs/chat.log（按天滚动，保留 30 天）
 */
public final class ConversationLogger {

    private static final Logger log = LoggerFactory.getLogger(ConversationLogger.class);

    private ConversationLogger() {}

    /** 记录用户消息 */
    public static void userMessage(String sessionId, String message) {
        log.info("[用户] session={}, 内容: {}", sessionId, truncate(message));
    }

    /** 记录 AI 回复 */
    public static void aiResponse(String sessionId, String model, String response) {
        log.info("[AI] session={}, model={}, 回复长度={}", sessionId, model,
                response != null ? response.length() : 0);
    }

    /** 记录工作流启动 */
    public static void workflowStart(String threadId, String requirement) {
        log.info("[工作流-启动] threadId={}, 需求: {}", threadId, truncate(requirement));
    }

    /** 记录工作流审核 */
    public static void workflowReview(String threadId, String decision, String comment) {
        log.info("[工作流-审核] threadId={}, 决策={}, 备注={}", threadId, decision, comment);
    }

    /** 记录工作流完成 */
    public static void workflowComplete(String threadId, String status) {
        log.info("[工作流-完成] threadId={}, 状态={}", threadId, status);
    }

    /** 记录代码生成 */
    public static void codeGeneration(String threadId, int fileCount) {
        log.info("[代码生成] threadId={}, 生成文件数={}", threadId, fileCount);
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
