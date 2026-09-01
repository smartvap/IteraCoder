package com.agenthub.ai.workflow.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.agenthub.ai.workflow.event.WorkflowEventBus;
import com.agenthub.ai.workflow.interceptor.SseStreamingInterceptor;
import com.agenthub.ai.workflow.service.DockerSandboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * DinD 安全沙箱工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SandboxTools {

    private final DockerSandboxService sandboxService;
    private final WorkflowEventBus eventBus;

    /**
     * 工具调用统计：key = threadId, value = 已调用的工具名集合。
     * 用于校验 harness Agent 是否真正调用了验证工具（防 LLM 捏造报告）。
     */
    private static final Map<String, java.util.Set<String>> toolUsage = new java.util.concurrent.ConcurrentHashMap<>();

    /** 记录某 threadId 已调用的工具名 */
    private static void recordToolUsage(String threadId, String toolName) {
        if (threadId == null || threadId.isBlank()) return;
        toolUsage.computeIfAbsent(threadId, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(toolName);
    }

    /**
     * 判断某 threadId 是否真正调用了指定工具（供 ValidationNode 校验）。
     * 未调用却声称成功 → 判定为捏造报告。
     */
    public static boolean hasCalledTool(String threadId, String toolName) {
        if (threadId == null) return false;
        java.util.Set<String> used = toolUsage.get(threadId);
        return used != null && used.contains(toolName);
    }

    /** 清理某 threadId 的工具调用统计（工作流结束时调用） */
    public static void clearToolUsage(String threadId) {
        if (threadId != null) {
            toolUsage.remove(threadId);
        }
    }

    /**
     * 获取当前活跃工作流的沙箱容器 ID。
     * <p>
     * harnessAgent 使用 asNode(true,false)，StateGraph 框架可能在不同线程执行节点，
     * ThreadLocal 无法跨线程传递 containerId。
     * 通过 SseStreamingInterceptor.getActiveThreadId("harness_result") 获取 threadId，
     * 再从 SandboxContext 的全局 Map 中按 threadId 查找 containerId。
     *
     * @return containerId，未找到则返回 null
     */
    private String getContainerId() {
        // 优先 ThreadLocal（同线程场景）
        String containerId = SandboxContext.getContainerId();
        if (containerId != null) {
            return containerId;
        }
        // 跨线程场景：通过 SseStreamingInterceptor 全局注册表获取 threadId
        String threadId = SseStreamingInterceptor.getActiveThreadId("harness_result");
        if (threadId != null) {
            containerId = SandboxContext.getContainerIdForThread(threadId);
            if (containerId != null) {
                log.debug("跨线程获取 containerId: threadId={}, containerId={}",
                        threadId, containerId.substring(0, Math.min(8, containerId.length())));
                return containerId;
            }
        }
        return null;
    }

    @Tool(description = "在 DinD 安全沙箱中编译代码。传入主文件路径或文件名即可。")
    public String compileCode(@ToolParam(description = "主文件相对路径或文件名") String fileName) {
        recordToolUsage(SseStreamingInterceptor.getActiveThreadId("harness_result"), "compileCode");
        if (!sandboxService.isAvailable()) {
            return "COMPILE_FAILED: Docker 不可用，无法执行编译。请启动 Docker 后重试。";
        }
        String containerId = getContainerId();
        if (containerId == null) return "COMPILE_FAILED: 沙箱容器未就绪";

        log.info("沙箱编译: containerId={}, fileName={}", containerId.substring(0, 8), fileName);
        pushProgress("🔨 沙箱编译中（需下载依赖+编译，约3-5分钟）: " + fileName + " (container=" + containerId.substring(0, 8) + ")");
        DockerSandboxService.ExecResult r = sandboxService.exec(containerId, 300,
                "sh", "-c", compileCommand(fileName));
        pushProgress(r.success() ? "✅ 编译通过" : "❌ 编译失败，正在分析错误...");

        // 合并 stdout + stderr（2>&1 后大部分在 stdout，但防御性合并确保不遗漏）
        String rawOutput = mergeOutput(r.stdout(), r.stderr());
        String filtered = filterMavenOutput(rawOutput);
        log.info("沙箱编译结果: exitCode={}, rawLen={}, filteredLen={}",
                r.exitCode(), rawOutput.length(), filtered.length());
        if (!r.success() && filtered.isBlank()) {
            log.warn("编译失败但过滤后为空！rawOutput 前500字符: {}", rawOutput.substring(0, Math.min(500, rawOutput.length())));
        }
        if (r.success()) {
            return "COMPILE_SUCCESS: " + fileName + " 编译通过";
        }
        if (filtered.isBlank()) {
            filtered = "（编译失败但未捕获到错误详情，exitCode=" + r.exitCode() + "）";
        }
        SandboxContext.setLastErrorDetail(filtered);
        return "COMPILE_FAILED:\n" + truncate(aggregateCompileErrors(filtered), 4000);
    }

    @Tool(description = "在 DinD 安全沙箱中运行代码并返回运行日志。传入主类全限定名或主文件路径即可。")
    public String executeInSandbox(@ToolParam(description = "主类全限定名或主文件路径") String entry) {
        recordToolUsage(SseStreamingInterceptor.getActiveThreadId("harness_result"), "executeInSandbox");
        if (!sandboxService.isAvailable()) {
            return "RUNTIME_ERROR: Docker 不可用，无法执行运行验证。请启动 Docker 后重试。";
        }
        String containerId = getContainerId();
        if (containerId == null) return "RUNTIME_ERROR: 沙箱容器未就绪";

        log.info("沙箱运行: containerId={}, entry={}", containerId.substring(0, 8), entry);
        pushProgress("▶️ 沙箱运行: " + entry);
        DockerSandboxService.ExecResult r = sandboxService.exec(containerId, 60,
                "sh", "-c", runCommand(entry));
        String filtered = filterMavenOutput(mergeOutput(r.stdout(), r.stderr()));
        if (r.success()) {
            return "RUNTIME_SUCCESS: 程序正常退出，exitCode=0\n" + truncate(filtered, 3000);
        }
        // Web 应用检测：mvn spring-boot:run 在超时后被 kill → exitCode≠0。
        // 如果日志显示 Spring Boot 正常启动（"Started XxxApplication in N seconds"
        // 或 Tomcat 正常监听端口），说明代码本身没问题，标记为 RUNTIME_SUCCESS。
        boolean webAppStarted = filtered != null && (
                filtered.contains("Tomcat started on port")
                        || (filtered.contains("Started ") && filtered.contains(" in "))
        );
        if (webAppStarted) {
            return "RUNTIME_SUCCESS: 应用正常启动（超时后进程被终止，非实际错误）\n"
                    + truncate(filtered, 3000);
        }
        // 白名单策略：检测日志中是否有真正的运行时异常
        // Spring Boot 的常见致命异常标志，出现这些才判定为真正的运行失败
        boolean hasRuntimeException = filtered != null && (
                filtered.contains("Caused by:")
                        || filtered.contains("APPLICATION FAILED TO START")
                        || filtered.contains("Error starting ApplicationContext")
                        || filtered.contains("Exception in thread")
        );
        if (!hasRuntimeException) {
            return "RUNTIME_SUCCESS: 编译通过，未检测到运行时异常（进程在超时或资源限制下被终止）\n"
                    + truncate(filtered, 3000);
        }
        if (filtered.isBlank()) {
            filtered = "（运行失败但未捕获到错误详情，exitCode=" + r.exitCode() + "）";
        }
        return "RUNTIME_ERROR: exitCode=" + r.exitCode() + "\n" + extractErrors(filtered, 4000);
    }

    @Tool(description = "在 DinD 安全沙箱中运行单元测试。传入测试类标识或文件名即可。")
    public String runTests(@ToolParam(description = "测试类标识或测试文件名") String artifact) {
        recordToolUsage(SseStreamingInterceptor.getActiveThreadId("harness_result"), "runTests");
        if (!sandboxService.isAvailable()) {
            return "TEST_FAILED: Docker 不可用，无法执行测试。请启动 Docker 后重试。";
        }
        String containerId = getContainerId();
        if (containerId == null) return "TEST_FAILED: 沙箱容器未就绪";

        log.info("沙箱测试: containerId={}, artifact={}", containerId.substring(0, 8), artifact);
        pushProgress("🧪 沙箱测试: " + artifact);
        DockerSandboxService.ExecResult r = sandboxService.exec(containerId, 180,
                "sh", "-c", testCommand());
        String filtered = filterMavenOutput(mergeOutput(r.stdout(), r.stderr()));
        if (r.success()) {
            return "TEST_SUCCESS: 全部测试通过\n" + truncate(filtered, 3000);
        }
        if (filtered.isBlank()) {
            filtered = "（测试失败但未捕获到错误详情，exitCode=" + r.exitCode() + "）";
        }
        return "TEST_FAILED: 存在未通过的测试\n" + truncate(filtered, 4000);
    }

    // ---------- command builders ----------

    private String compileCommand(String fileName) {
        if (fileName != null && fileName.endsWith(".py")) {
            return "python3 -m py_compile /workspace/" + fileName;
        }
        // 不用 -q：-q 会抑制编译器错误详情输出，导致修复 Agent 无法看到错误
        // --no-transfer-progress: 禁止下载进度输出（Downloading/Downloaded/Progress 行）
        // filterMavenOutput 会过滤掉 [INFO] 噪音行，保留 [ERROR] 错误详情
        return "mvn compile --no-transfer-progress 2>&1";
    }

    private String runCommand(String entry) {
        if (entry != null && entry.endsWith(".py")) {
            return "python3 /workspace/" + entry;
        }
        if (entry != null && entry.contains(".")) {
            return "mvn spring-boot:run -Dspring-boot.run.mainClass=" + entry.trim() + " --no-transfer-progress 2>&1";
        }
        return "mvn exec:java -Dexec.mainClass=com.example.Application --no-transfer-progress 2>&1";
    }

    private String testCommand() {
        // 不用 -q：-q 会抑制测试失败详情输出
        return "mvn test --no-transfer-progress 2>&1";
    }

    // ---------- helpers ----------

    /** 推送沙箱操作进度到 SSE 横幅（增量模式） */
    private void pushProgress(String message) {
        if (eventBus == null) return;
        try {
            String threadId = SseStreamingInterceptor.getActiveThreadId("harness_result");
            if (threadId != null) {
                eventBus.publishDelta(threadId, "harness_result", message + "\n", "RUNNING");
            }
        } catch (Exception ignored) {
            // 进度推送失败不影响工具执行
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.isBlank()) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\n... (truncated)";
    }

    /**
     * 聚合编译错误：去重完全相同的行 + 同根因错误最多保留 2 行。
     * <p>
     * 场景：同一文件多处调用 getFont() 缺失时，Maven 输出 15+ 行 [ERROR]，
     * 但根因只有 1 个（方法不存在）。聚合后 LLM 看到 2 行 + 计数提示，
     * 减少信息过载，避免 Agent 因错误太多而"观望"浪费修复轮次。
     */
    private static String aggregateCompileErrors(String filtered) {
        if (filtered == null || filtered.isBlank()) return filtered;

        String[] lines = filtered.split("\n", -1);
        // 第一遍：收集所有 [ERROR] 行及其分组密钥
        List<ErrorLine> errors = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (!t.startsWith("[ERROR]")) continue;
            errors.add(new ErrorLine(line, t));
        }
        if (errors.isEmpty()) return filtered;

        // 去重：完全相同的行只保留一次
        Map<String, Integer> exactDedup = new LinkedHashMap<>();
        List<ErrorLine> deduped = new ArrayList<>();
        for (ErrorLine e : errors) {
            Integer cnt = exactDedup.compute(e.trimmed, (k, v) -> (v == null) ? 1 : v + 1);
            if (cnt == 1) deduped.add(e);
        }
        errors = deduped;

        // 根因分组：同文件 + 同错误类型的错误聚合，每组最多保留 2 行
        Map<String, GroupedErrors> groups = new LinkedHashMap<>();
        for (ErrorLine e : errors) {
            String key = groupKey(e.trimmed);
            groups.computeIfAbsent(key, k -> new GroupedErrors()).add(e);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, GroupedErrors> entry : groups.entrySet()) {
            GroupedErrors g = entry.getValue();
            // 保留前 2 行，其余折为计数提示
            int show = Math.min(g.lines.size(), 2);
            for (int i = 0; i < show; i++) {
                sb.append(g.lines.get(i).original).append("\n");
            }
            if (g.total > show) {
                sb.append("    ↑ 其他 ").append(g.total - show).append(" 处类似错误已省略\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * 生成错误分组 key，同文件 + 同错误类型算一组。
     * 例如 "cannot find symbol: method getFont()" → "file:WordGeneratorImpl|type:cannot find symbol"
     */
    private static String groupKey(String trimmed) {
        // 尝试提取文件路径
        String file = "";
        int colon = trimmed.indexOf(".java:");
        if (colon > 0) {
            int start = trimmed.lastIndexOf('/', colon);
            if (start < 0) start = trimmed.lastIndexOf('\\', colon);
            file = trimmed.substring(Math.max(0, start + 1), colon + ".java".length());
        }
        // 提取错误类型
        String type = "OTHER";
        if (trimmed.contains("cannot find symbol")) type = "cannot find symbol";
        else if (trimmed.contains("constructor") && trimmed.contains("cannot be applied")) type = "constructor mismatch";
        else if (trimmed.contains("does not exist")) type = "package/class missing";
        else if (trimmed.contains("incompatible types")) type = "incompatible types";
        else if (trimmed.contains("is not abstract")) type = "missing override";
        else if (trimmed.contains("has already been defined")) type = "duplicate";
        else type = "OTHER";
        return "file:" + file + "|type:" + type;
    }

    /** 编译错误一条记录 */
    private static class ErrorLine {
        final String original;
        final String trimmed;
        ErrorLine(String original, String trimmed) { this.original = original; this.trimmed = trimmed; }
    }

    /** 同文件 + 同类型的分组统计 */
    private static class GroupedErrors {
        final List<ErrorLine> lines = new ArrayList<>();
        int total;
        void add(ErrorLine e) { lines.add(e); total++; }
    }

    /**
     * 截断但优先保留尾部内容。
     * 用于运行时错误路径：Spring Boot 启动失败时异常堆栈通常在日志末尾
     * （APPLICATION FAILED TO START + Caused by），头部截断会丢失关键堆栈，
     * 导致修复 Agent 无法定位问题。保留头部少量 + 尾部大部分。
     */
    private static String truncateTail(String s, int maxLen) {
        if (s == null || s.isBlank()) return "";
        if (s.length() <= maxLen) return s;
        int tailLen = (int) (maxLen * 0.8);
        int headLen = maxLen - tailLen - "\n... (truncated)...\n".length();
        if (headLen < 0) headLen = 0;
        String head = s.substring(0, headLen);
        String tail = s.substring(s.length() - tailLen);
        return head + "\n... (truncated)...\n" + tail;
    }

    /**
     * 从完整日志中提取错误/异常关键行（类似 grep -C 10），供修复 Agent 定位问题。
     * <p>
     * 比单纯截断更可靠：异常堆栈可能出现在日志任意位置（不一定是尾部），
     * 只保留尾部会丢失中间的 Caused by。策略（等价于 grep -C 10）：
     * <ol>
     *   <li>扫描完整日志，标记异常关键行（Caused by / Exception / Error starting /
     *       APPLICATION FAILED / java.lang.* / ERROR）</li>
     *   <li>保留每个关键行前后各 10 行上下文（关键行本身 + 前 10 行 + 后 10 行）</li>
     *   <li>提取内容超长时再截断（保留尾部）</li>
     *   <li>未提取到任何异常 → 回退 truncateTail 保留尾部</li>
     * </ol>
     */
    private static String extractErrors(String fullLog, int maxLen) {
        if (fullLog == null || fullLog.isBlank()) return "";
        if (fullLog.length() <= maxLen) return fullLog;

        String[] lines = fullLog.split("\n", -1);
        final int CONTEXT = 10; // grep -C 10：关键行前后各 10 行
        java.util.Set<Integer> keepIndexes = new java.util.LinkedHashSet<>();

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("Caused by:")
                    || trimmed.contains("APPLICATION FAILED TO START")
                    || trimmed.contains("Error starting ApplicationContext")
                    || trimmed.contains("Exception in thread")
                    || trimmed.contains("ERROR")
                    || trimmed.startsWith("java.lang.")
                    || trimmed.startsWith("org.springframework.")
                    || trimmed.startsWith("org.hibernate.")) {
                // 保留关键行前后各 CONTEXT 行
                for (int j = Math.max(0, i - CONTEXT);
                     j <= Math.min(lines.length - 1, i + CONTEXT); j++) {
                    keepIndexes.add(j);
                }
            }
        }

        if (keepIndexes.isEmpty()) {
            // 无异常关键行 → 保留尾部（Web 应用启动进展）
            return truncateTail(fullLog, maxLen);
        }

        StringBuilder sb = new StringBuilder();
        for (Integer idx : keepIndexes) {
            if (!lines[idx].isBlank()) {
                sb.append(lines[idx]).append("\n");
            }
        }
        String result = sb.toString();
        return result.length() > maxLen ? truncateTail(result, maxLen) : result;
    }

    /**
     * 合并 stdout 和 stderr，确保不遗漏任何输出。
     * 由于 Maven 命令使用 2>&1，大部分输出在 stdout，但防御性合并确保完整性。
     */
    private static String mergeOutput(String stdout, String stderr) {
        StringBuilder sb = new StringBuilder();
        if (stdout != null && !stdout.isBlank()) sb.append(stdout);
        if (stderr != null && !stderr.isBlank()) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(stderr);
        }
        return sb.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b != null ? b : "");
    }

    /**
     * 过滤 Maven/构建输出中的噪音行，保留关键诊断信息。
     * <p>
     * 核心原则：编译失败时只保留 [ERROR] 和错误详情行；
     * 编译成功时只保留 BUILD SUCCESS 和测试统计。
     * <p>
     * 过滤掉的噪音模式：
     * - 所有纯 [INFO] 行（除非包含 BUILD/Tests run 等关键字）
     * - Downloading/Downloaded from ...（Maven 依赖下载进度）
     * - Progress (N): ...（下载进度百分比）
     * - 空行、分隔线（----/====）
     * <p>
     * 保留的关键信息：
     * - [ERROR] 行（编译错误、测试失败）
     * - [WARNING] 行（重要警告）
     * - BUILD SUCCESS / BUILD FAILURE
     * - Tests run: ...（测试统计）
     * - COMPILATION ERROR / cannot find symbol 等具体错误信息
     * - Exception 堆栈
     * - 符号错误上下文（location: variable: class: 等）
     */
    private static String filterMavenOutput(String output) {
        if (output == null || output.isBlank()) return "";
        String cleaned = stripAnsi(output);
        String[] lines = cleaned.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 跳过分隔线（如 --------/========）
            if (trimmed.matches("^[-=]{10,}.*")) continue;

            // 跳过 Maven 下载进度噪音
            if (trimmed.startsWith("Downloading from") || trimmed.startsWith("Downloaded from")) continue;
            if (trimmed.startsWith("Progress (")) continue;

            // 核心策略：过滤掉所有纯 [INFO] 行，除非包含关键标记
            if (trimmed.startsWith("[INFO]")) {
                // 保留包含 BUILD/Tests run 的 [INFO] 行
                if (trimmed.contains("BUILD SUCCESS") || trimmed.contains("BUILD FAILURE")) {
                    sb.append(line).append("\n");
                }
                if (trimmed.contains("Tests run:")) {
                    sb.append(line).append("\n");
                }
                // 其他所有 [INFO] 行一律跳过
                continue;
            }

            // 过滤 WARNING 噪音（编码警告、平台警告等，对修复诊断无帮助）
            if (trimmed.startsWith("[WARNING]")) {
                // 只保留包含具体文件名+行号的 [WARNING]（如使用了已弃用 API）
                if (trimmed.contains(".java:") || trimmed.contains(".java]")) {
                    sb.append(line).append("\n");
                }
                continue;
            }

            // 过滤 Maven 错误帮助样板（编译失败后多出来的提示信息）
            // 如 "[ERROR] -> [Help 1]" "[ERROR] To see..." "[ERROR] Re-run..." 等
            if (trimmed.matches("(?i)\\[ERROR\\]\\s*(->\\s*)?(\\[Help|To see|Re-run|For more|\\[Help).*")) continue;
            // 过滤 Maven 帮助 URL 行
            if (trimmed.matches("(?i)\\[ERROR\\]\\s*https?://.*")) continue;

            // 保留所有非 [INFO] 行（[ERROR]/[WARNING]/错误详情/堆栈等）
            sb.append(line).append("\n");
        }
        String result = sb.toString();
        // 如果过滤后为空但原始有内容，返回截断的原始输出（防止误过滤）
        if (result.isBlank()) {
            return truncate(cleaned, 3000);
        }
        return result;
    }

    /** 去除 ANSI 终端颜色码（[1;31m 等） */
    private static final java.util.regex.Pattern ANSI = java.util.regex.Pattern.compile("\u001B\\[[0-9;]*[mK]");
    private static String stripAnsi(String s) {
        return s == null ? "" : ANSI.matcher(s).replaceAll("");
    }

    // ---------- Mock fallback ----------

    private String mockCompile(String fileName) {
        if (fileName == null || fileName.isBlank()) return "COMPILE_FAILED: 文件名为空";
        if (fileName.contains("SYNTAX_ERROR")) return "COMPILE_FAILED: 语法错误，请修复";
        return "COMPILE_SUCCESS: " + fileName + " 编译通过（Mock）";
    }

    private String mockExecute(String entry) {
        if (entry == null || entry.isBlank()) return "RUNTIME_ERROR: 标识为空";
        return "RUNTIME_SUCCESS: 程序正常退出，exitCode=0（Mock）";
    }

    private String mockTests(String artifact) {
        if (artifact == null || artifact.isBlank()) return "TEST_FAILED: 无可执行产物";
        if (artifact.contains("TEST_FAIL") || artifact.contains("SYNTAX_ERROR"))
            return "TEST_FAILED: 2 个用例失败";
        return "TEST_SUCCESS: 全部 5 个测试通过（Mock）";
    }
}
