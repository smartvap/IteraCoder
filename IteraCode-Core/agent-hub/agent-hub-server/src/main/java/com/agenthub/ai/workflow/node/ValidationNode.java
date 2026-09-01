package com.agenthub.ai.workflow.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.agenthub.ai.workflow.constant.RdWorkflowKeys;
import com.agenthub.ai.workflow.event.WorkflowEventBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.Map;

/**
 * 验证结果判断节点：解析 Harness 执行结果，决定通过或进入修复循环。
 * <p>
 * 本节点为纯逻辑节点（无 LLM 调用），执行速度极快。
 * 为提供前端可见的进度反馈，在执行前后通过 WorkflowEventBus 推送事件。
 */
@Slf4j
public class ValidationNode implements NodeAction {

    private final WorkflowEventBus eventBus;

    public ValidationNode(WorkflowEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        // 推送"开始验证"事件，让前端步骤变蓝
        pushEvent(state, "正在分析沙箱验证结果...");

        Object harnessResultObj = state.value(RdWorkflowKeys.HARNESS_RESULT, "");
        String harnessResult;

        if (harnessResultObj instanceof AssistantMessage msg) {
            harnessResult = msg.getText();
        } else {
            // 尝试 GraphResponse 包装（harnessAgent 使用 asNode 输出时）
            harnessResult = extractText(harnessResultObj);
        }
        // 诊断：确认重复 COMPILE_SUCCESS 是 LLM 报告本身还是拼接产生
        log.info("[HARNESS-DIAG] 原始 harnessResult 长度={}, COMPILE_SUCCESS 出现次数={}",
                harnessResult == null ? -1 : harnessResult.length(),
                harnessResult == null ? -1 : countOccurrences(harnessResult, "COMPILE_SUCCESS"));
        log.info("[HARNESS-DIAG] 原始 harnessResult 全文: {}",
                harnessResult == null ? "<null>" : harnessResult);
        // 防误判：使用 contains 精确匹配工具返回的关键标记。
        // 之前的正则 (?s).*COMPILE_SUCCESS:\s*true\s*$.* 中 (?s) 是 DOTALL 而非 MULTILINE，
        // $ 只匹配整个文本末尾而非行尾，导致只有最后一行的标记能匹配，三 true 仍判失败。
        boolean compileOk = harnessResult.contains("COMPILE_SUCCESS: true")
                && !harnessResult.contains("COMPILE_SUCCESS: false");
        boolean hasRuntime = (harnessResult.contains("RUNTIME_SUCCESS: true")
                || harnessResult.contains("RUNTIME_SUCCESS: 编译通过")
                || harnessResult.contains("RUNTIME_SUCCESS: 应用正常启动"))
                && !harnessResult.contains("RUNTIME_SUCCESS: false");
        boolean testOk = harnessResult.contains("TEST_SUCCESS: true")
                && !harnessResult.contains("TEST_SUCCESS: false");
        boolean harnessRan = compileOk || hasRuntime;
        boolean hasFailure = harnessResult.contains("FAILED") || harnessResult.contains("ERROR");
        // Web 应用超时兜底：即使 harness 返回 RUNTIME_ERROR（进程被超时 kill），
        // 如果日志显示 Spring Boot 正常启动（"Started XxxApplication in N seconds"
        // 或 Tomcat 正常监听端口），说明代码本身没问题，覆盖误判。
        boolean webAppStarted = harnessResult.contains("Tomcat started on port")
                || (harnessResult.contains("Started ") && harnessResult.contains(" in "));
        if (webAppStarted) {
            hasRuntime = true;
            hasFailure = false; // 覆盖 RUNTIME_ERROR 中的 ERROR 字眼
        }
        harnessRan = compileOk || hasRuntime; // 重新计算（因为 hasRuntime 可能被 webAppStarted 覆盖）
        // 检测模板占位符：LLM 可能没粘贴真实工具返回值，而是保留模板文本
        // 如 "[compileCode 返回值，必须完整粘贴，不要省略或截断]" 说明没替换
        boolean hasPlaceholder = harnessResult.contains("[compileCode 返回值")
                || harnessResult.contains("[executeInSandbox 返回值")
                || harnessResult.contains("[runTests 返回值");
        // 关键：同时有 COMPILE_SUCCESS: true 和模板占位符 → LLM 捏造了成功报告（没有粘贴真实工具返回值）
        // 这种情况必须判为失败，不能进入"修复 Agent 看到全绿报告却不知修什么"的死循环
        boolean fakeSuccess = harnessRan && hasPlaceholder;
        // 方案A：工具调用校验——harness Agent 声称验证通过，但根本没调用 compileCode 工具 → 捏造。
        // 从工具调用源头强制，不信任 LLM 报告（LLM 可能不调工具却编造 true 标记）。
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        boolean compileClaimedTrue = harnessResult.contains("COMPILE_SUCCESS: true")
                && !harnessResult.contains("COMPILE_SUCCESS: false");
        boolean noToolCall = threadId != null
                && compileClaimedTrue
                && !com.agenthub.ai.workflow.tool.SandboxTools.hasCalledTool(threadId, "compileCode");
        if (noToolCall) {
            log.warn("验证节点：harness Agent 声称 COMPILE_SUCCESS: true 但未调用 compileCode 工具 → 判定捏造 (threadId={})", threadId);
        }
        boolean passed = harnessRan && compileOk && testOk && !hasFailure && !fakeSuccess && !noToolCall;

        log.info("验证节点：harness 结果={}, 验证{}", harnessResult, passed ? "通过" : "失败");

        // 推送验证结论
        String conclusion = passed
                ? "✅ 验证通过：编译成功且测试全部通过"
                : "❌ 验证失败：存在编译错误或测试未通过，进入修复循环";
        pushEvent(state, conclusion);

        if (passed) {
            return Map.of(
                    RdWorkflowKeys.VALIDATION_PASSED, true,
                    RdWorkflowKeys.WORKFLOW_STATUS, "COMPLETED",
                    RdWorkflowKeys.WORKFLOW_MESSAGE, "验证通过，工作流执行完成"
            );
        }
        return Map.of(
                RdWorkflowKeys.VALIDATION_PASSED, conclusion,  // 失败原因文本（非 boolean false，前端可持久展示）
                RdWorkflowKeys.WORKFLOW_MESSAGE, "验证失败，进入代码修复循环"
        );
    }

    private void pushEvent(OverAllState state, String content) {
        if (eventBus == null) return;
        String threadId = state.value(MultiRoundAgentNode.THREAD_ID_KEY).map(Object::toString).orElse(null);
        if (threadId == null) return;
        eventBus.publish(threadId, RdWorkflowKeys.VALIDATION_PASSED, content, "RUNNING");
    }

    /** 从 GraphResponse / Message 等包装类型中提取纯文本 */
    private String extractText(Object obj) {
        if (obj == null) return "";
        if (obj instanceof AssistantMessage msg) return msg.getText();
        if (obj instanceof Message msg) return msg.getText();
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod("resultValue");
            Object opt = m.invoke(obj);
            if (opt instanceof java.util.Optional<?> o && o.isPresent()) {
                return extractText(o.get());
            }
        } catch (Exception ignored) {}
        return obj.toString();
    }

    private static int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
