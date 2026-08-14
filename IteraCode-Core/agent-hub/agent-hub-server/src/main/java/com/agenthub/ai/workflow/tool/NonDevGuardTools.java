package com.agenthub.ai.workflow.tool;

import com.agenthub.ai.workflow.interceptor.SseStreamingInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 非研发需求防护工具：强制 Agent 声明意图意图类型，代码层检测防止 prompt injection 越狱。
 * <p>
 * 用法：将 {@code NonDevGuardTools} 注册到需求拆解 Agent 的 methodTools 中。
 * requirement-analysis.md 步骤0 要求必须先调用 {@code reportAssessment}。
 * DecompositionGateNode 读取调用记录判断是否放行。
 */
@Slf4j
@Component
public class NonDevGuardTools {

    private static final Map<String, Boolean> ASSESSMENTS = new ConcurrentHashMap<>();

    /**
     * 评估用户输入意图类型 — Agent 必须首先调用此工具。
     *
     * @param isDevReq true=研发需求, false=非研发需求
     * @return 评估结果标记
     */
    @Tool(description = "【必须首先调用】声明用户输入的类型：true=研发需求，false=非研发需求")
    public String reportAssessment(@ToolParam(description = "true=研发需求, false=非研发需求") boolean isDevReq) {
        String threadId = SseStreamingInterceptor.getActiveThreadId("decomposition_result");
        if (threadId != null) {
            ASSESSMENTS.put(threadId, isDevReq);
            log.info("reportAssessment: threadId={}, isDevReq={}", threadId, isDevReq);
        }
        return isDevReq ? "ASSESSMENT_DEV_REQ" : "ASSESSMENT_NOT_DEV_REQ";
    }

    /**
     * 获取并清除指定 threadId 的评估结果，供 DecompositionGateNode 读取。
     *
     * @return null 表示工具未被调用（拦截），true=研发需求，false=非研发需求
     */
    public static Boolean getAndClear(String threadId) {
        return ASSESSMENTS.remove(threadId);
    }
}
