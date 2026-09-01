package com.agenthub.ai.workflow.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.agenthub.ai.workflow.interceptor.SseStreamingInterceptor;
import com.agenthub.ai.workflow.tool.CodeProjectWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代码修复工具：修复 Agent 通过 Tool 调用直接写文件，不依赖文本格式解析。
 */
@Slf4j
@Component
public class CodeRepairTools {

    /**
     * 文件写入去重 + 防无限循环：
     * key = threadId + ":" + filePath, value = 最后写入内容的 hashCode。
     * 连续 3 次相同内容写入同一文件时，返回强制终止消息让 LLM 停止调用。
     */
    private static final Map<String, Integer> writtenContentHashes = new ConcurrentHashMap<>();
    /** 连续重复写入计数器，key 同上，达到阈值后返回终止消息 */
    private static final Map<String, Integer> duplicateStreak = new ConcurrentHashMap<>();
    /** 连续重复写入阈值：超过此次数后返回终止消息 */
    private static final int MAX_DUPLICATE_STREAK = 3;

    /**
     * 工具使用统计：key = threadId, value = 已调用的工具名集合。
     * 用于检测模型是否遵循"readCodeFile → patchCodeFile"定点修改流程，
     * 若只调用 writeCodeFile 全量重写，则通过返回消息引导纠正。
     */
    private static final Map<String, java.util.Set<String>> toolUsageStats = new ConcurrentHashMap<>();

    /** 记录某 threadId 已调用的工具名 */
    private static void recordToolUsage(String threadId, String toolName) {
        if (threadId == null || threadId.isBlank()) return;
        toolUsageStats.computeIfAbsent(threadId, k -> ConcurrentHashMap.newKeySet()).add(toolName);
    }

    /**
     * 检查某 threadId 本轮是否只用了 writeCodeFile 而完全没用 readCodeFile + patchCodeFile。
     * 返回 true 表示需要提示模型改用定点修改流程。
     */
    private static boolean onlyFullRewrite(String threadId) {
        if (threadId == null) return false;
        java.util.Set<String> used = toolUsageStats.get(threadId);
        if (used == null || used.isEmpty()) return false;
        boolean usedRead = used.contains("readCodeFile");
        boolean usedPatch = used.contains("patchCodeFile");
        boolean usedWrite = used.contains("writeCodeFile");
        return usedWrite && !usedRead && !usedPatch;
    }

    /** 清理某 threadId 的工具使用统计（工作流结束时调用） */
    public static void clearToolUsage(String threadId) {
        if (threadId != null) {
            toolUsageStats.remove(threadId);
        }
    }

    /**
     * 判断某 threadId 本轮修复是否实际调用了文件写入工具（writeCodeFile / patchCodeFile）。
     * 供条件边判定 hasFiles 使用：修复 Agent 必须真正写入文件才算有效，
     * 仅输出文本（含伪调用）不算。
     */
    public static boolean hasWrittenThisRound(String threadId) {
        if (threadId == null) return false;
        java.util.Set<String> used = toolUsageStats.get(threadId);
        if (used == null || used.isEmpty()) return false;
        return used.contains("writeCodeFile") || used.contains("patchCodeFile");
    }

    @Tool(description = "将修复后的代码写入文件。参数 filePath 为相对路径（如 pom.xml、src/main/java/App.java），content 为文件完整内容。仅当文件缺失（新建文件）或需要全量重写时调用此工具。修改已有文件的局部代码请优先使用 patchCodeFile。")
    public String writeCodeFile(
            @ToolParam(description = "文件相对路径，如 pom.xml 或 src/main/java/com/example/App.java") String filePath,
            @ToolParam(description = "文件完整代码内容") String content) {

        if (filePath == null || filePath.isBlank()) {
            return "错误：filePath 不能为空，请重新调用并提供有效的文件路径";
        }
        if (content == null || content.isBlank()) {
            return "错误：content 不能为空，请生成完整的文件内容后重新调用";
        }

        try {
            String threadId = resolveCurrentThreadId();
            String root = resolveRepairRoot(threadId);

            // 去掉可能的 /workspace/ 前缀
            String relative = filePath.replaceFirst("^/workspace/", "");
            // 清理 LLM 可能混入的 markdown 代码块标记 + 尾部噪声（标题、中文描述等）
            String cleanedContent = CodeProjectWriter.stripTrailingNoise(
                    CodeProjectWriter.stripCodeBlockMarkers(content));

            // 去重检查 + 防无限循环：防止 LLM 对同一文件重复调用 writeCodeFile
            String dedupKey = (threadId != null ? threadId : "global") + ":" + relative;
            int contentHash = cleanedContent.hashCode();
            Integer lastHash = writtenContentHashes.get(dedupKey);
            if (lastHash != null && lastHash == contentHash) {
                int streak = duplicateStreak.merge(dedupKey, 1, Integer::sum);
                if (streak >= MAX_DUPLICATE_STREAK) {
                    log.warn("writeCodeFile 连续重复 {} 次，返回终止消息: {}", streak, relative);
                    return "⛔ 停止调用！你已连续 " + streak + " 次对 " + relative
                            + " 写入相同内容。本轮所有修复文件已写入完毕，请立即输出修复总结报告（Step 5），不要再调用 writeCodeFile。";
                }
                log.info("writeCodeFile 跳过重复写入 (streak={}): {}", streak, relative);
                return "跳过重复写入: " + relative + "（内容未变化，已在上次调用中写入）。"
                        + " 请检查是否还有其他文件需要修复，如果没有则输出 Step 5 总结报告。";
            }
            // 内容变化了，重置 streak
            writtenContentHashes.put(dedupKey, contentHash);
            duplicateStreak.remove(dedupKey);

            Path target = Path.of(root, relative).normalize();
            // 防止路径穿越
            if (!target.startsWith(Path.of(root).toAbsolutePath().normalize())) {
                return "错误：非法文件路径: " + filePath;
            }

            // 记录工具使用：writeCodeFile 全量写入
            recordToolUsage(threadId, "writeCodeFile");

            // ⚠️ 程序化拦截：对已存在的文件做全量重写时，提醒优先用 patchCodeFile（防 JSON 截断、防破坏未修改部分）
            if (Files.exists(target)) {
                long existingLen = Files.size(target);
                log.info("writeCodeFile 对已存在文件全量重写: {} (现有 {} 字符 → 新 {} 字符), threadId={}",
                        relative, existingLen, cleanedContent.length(), threadId);
                // 仍然执行写入（确有全量重写场景），但返回消息明确提示
                Files.writeString(target, cleanedContent);
                String guide = "⚠️ 已全量重写已存在文件: " + relative + " (" + cleanedContent.length() + " 字符，原 "
                        + existingLen + " 字符)。"
                        + "注意：如果只是想修改该文件的某一行/某段/某个方法，下次请改用 patchCodeFile(filePath, oldCode, newCode)"
                        + "只传待替换片段和替换后片段（修改前先调用 readCodeFile 获取真实内容）。"
                        + "全量重写会带来 JSON 过长被截断的风险，只有新建文件时才应使用 writeCodeFile。";
                // 方案5：若本轮全程只用 writeCodeFile 而没用 read+patch，追加强提醒
                if (onlyFullRewrite(threadId)) {
                    guide += "\n【流程提醒】本轮你目前只调用了 writeCodeFile 全量重写，尚未使用 readCodeFile + patchCodeFile 定点修改流程。"
                            + "对已有文件的修改请先 readCodeFile 再 patchCodeFile，避免整文件重写引发 JSON 截断。";
                }
                return guide;
            }

            Files.createDirectories(target.getParent());
            Files.writeString(target, cleanedContent);
            log.info("writeCodeFile 新建文件: {} ({} 字符), threadId={}", relative, cleanedContent.length(), threadId);
            return "新建文件成功: " + relative + " (" + cleanedContent.length() + " 字符)";
        } catch (IOException e) {
            log.error("writeCodeFile 写入失败: {}", filePath, e);
            return "写入失败: " + e.getMessage() + "，请重试";
        }
    }

    /** 单次读取返回的最大字符数，超出则截断并提示 */
    private static final int MAX_READ_CHARS = 8000;

    /**
     * 读取修复目录中文件的当前内容。
     * 修复 Agent 需要先调用此工具获取文件的真实内容（注意：磁盘文件是代码生成输出清理后的版本，
     * 与任务描述中看到的 {generated_code} 可能有细微差异），再基于真实内容构造 oldCode 调用 patchCodeFile。
     * 返回内容过长时截断并提示（上限 MAX_READ_CHARS），避免单次工具返回过大。
     */
    @Tool(description = "读取修复目录中文件的当前内容。参数 filePath 为相对路径。调用 patchCodeFile 前应先调用此工具，获取文件真实的当前内容（磁盘文件与任务描述中展示的代码可能有细微差异，如尾部注释被清理），确保 oldCode 与文件逐字一致。")
    public String readCodeFile(
            @ToolParam(description = "文件相对路径，如 src/main/java/com/example/App.java") String filePath) {

        if (filePath == null || filePath.isBlank()) {
            return "错误：filePath 不能为空，请重新调用并提供有效的文件路径";
        }

        try {
            String threadId = resolveCurrentThreadId();
            String root = resolveRepairRoot(threadId);

            // 去掉可能的 /workspace/ 前缀
            String relative = filePath.replaceFirst("^/workspace/", "");
            Path target = Path.of(root, relative).normalize();
            // 防止路径穿越
            if (!target.startsWith(Path.of(root).toAbsolutePath().normalize())) {
                return "错误：非法文件路径: " + filePath;
            }
            if (!Files.exists(target)) {
                return "错误：文件不存在: " + relative + "。如果是新建文件，请使用 writeCodeFile 工具创建完整文件。";
            }
            if (Files.isDirectory(target)) {
                return "错误：路径是一个目录而非文件: " + relative;
            }

            recordToolUsage(threadId, "readCodeFile");

            String content = Files.readString(target);
            if (content.length() <= MAX_READ_CHARS) {
                // 内嵌指令：引导模型从返回内容中精确复制 oldCode
                return content + "\n\n---\n"
                        + "【读取完毕】以上是文件 " + relative + " 的真实当前内容。"
                        + "如需修改，请从上述内容中精确复制（含缩进换行）待替换片段作为 patchCodeFile 的 oldCode 参数，"
                        + "newCode 为替换后的新代码。不要凭记忆构造 oldCode。";
            }
            // 超长截断：保留开头和结尾（结尾包含类的收尾结构，便于模型理解整体）
            int keepHead = MAX_READ_CHARS * 2 / 3;
            String truncated = content.substring(0, keepHead)
                    + "\n\n... [文件过长已截断，共 " + content.length() + " 字符，仅展示开头 "
                    + keepHead + " 字符。如需修改文件中部/尾部内容，请基于上下文推断或使用 patchCodeFile 配合更精确的 oldCode 片段]\n\n"
                    + content.substring(content.length() - (MAX_READ_CHARS - keepHead));
            return truncated + "\n\n---\n"
                    + "【读取完毕】以上是文件 " + relative + " 的部分内容（过长已截断）。"
                    + "如需修改，请从上述可见内容中精确复制待替换片段作为 patchCodeFile 的 oldCode，不要凭记忆构造。";
        } catch (IOException e) {
            log.error("readCodeFile 读取失败: {}", filePath, e);
            return "读取失败: " + e.getMessage() + "，请重试";
        }
    }

    /**
     * 定点修改文件中的某段代码：在文件中查找 oldCode 片段并替换为 newCode。
     * 当只修改文件的某一行/某段/某个方法时使用，避免重写整个文件导致 JSON 过大被截断。
     * 仅当文件缺失（新建文件）或需要全量重写时才使用 writeCodeFile。
     */
    @Tool(description = "定点修改文件中的某段代码。参数 filePath 为相对路径，oldCode 为文件中现有的待替换代码片段（必须与文件内容精确匹配），newCode 为替换后的新代码。当只修改文件的某一行/某段/某个方法时使用此工具，不要重写整个文件。")
    public String patchCodeFile(
            @ToolParam(description = "文件相对路径，如 src/main/java/com/example/App.java") String filePath,
            @ToolParam(description = "文件中现有的待替换代码片段（必须与文件内容逐字匹配）") String oldCode,
            @ToolParam(description = "替换后的新代码片段") String newCode) {

        if (filePath == null || filePath.isBlank()) {
            return "错误：filePath 不能为空，请重新调用并提供有效的文件路径";
        }
        if (oldCode == null || oldCode.isBlank()) {
            return "错误：oldCode 不能为空，请提供文件中现有的待替换代码片段";
        }
        if (newCode == null) {
            return "错误：newCode 不能为空，请提供替换后的新代码";
        }

        try {
            String threadId = resolveCurrentThreadId();
            String root = resolveRepairRoot(threadId);

            // 去掉可能的 /workspace/ 前缀
            String relative = filePath.replaceFirst("^/workspace/", "");
            // 清理 LLM 可能混入的 markdown 代码块标记
            String cleanOld = CodeProjectWriter.stripCodeBlockMarkers(oldCode);
            String cleanNew = CodeProjectWriter.stripCodeBlockMarkers(newCode);

            Path target = Path.of(root, relative).normalize();
            // 防止路径穿越
            if (!target.startsWith(Path.of(root).toAbsolutePath().normalize())) {
                return "错误：非法文件路径: " + filePath;
            }
            if (!Files.exists(target)) {
                return "错误：文件不存在: " + relative + "。如果是新建文件，请使用 writeCodeFile 工具创建完整文件。";
            }

            String current = Files.readString(target);
            int idx = current.indexOf(cleanOld);
            if (idx < 0) {
                return "错误：在文件 " + relative + " 中未找到与 oldCode 精确匹配的代码片段。"
                        + "请检查 oldCode 是否与文件现有内容逐字一致（含缩进、换行），"
                        + "可尝试截取更长且唯一的片段（如包含方法签名或前后几行）。";
            }
            // 多匹配：要求模型提供更长上下文使匹配唯一
            int secondIdx = current.indexOf(cleanOld, idx + cleanOld.length());
            if (secondIdx >= 0) {
                return "错误：oldCode 在文件 " + relative + " 中出现多次，无法确定替换位置。"
                        + "请提供更长、更独特的 oldCode 片段（如包含方法签名或前后行）。";
            }

            recordToolUsage(threadId, "patchCodeFile");

            String updated = current.substring(0, idx) + cleanNew + current.substring(idx + cleanOld.length());
            Files.writeString(target, updated);
            log.info("patchCodeFile 修改: {} (替换 {} 字符 → {} 字符), threadId={}", relative, cleanOld.length(), cleanNew.length(), threadId);
            return "修改成功: " + relative + "（已定点替换目标代码片段，其余内容保持不变）。"
                    + "如还有其他文件需修改，请继续调用 readCodeFile + patchCodeFile；全部修复完成后输出 Step 5 总结报告。";
        } catch (IOException e) {
            log.error("patchCodeFile 修改失败: {}", filePath, e);
            return "修改失败: " + e.getMessage() + "，请重试";
        }
    }

    /**
     * 获取（或创建）当前工作流的修复临时目录。
     * 首次创建时复制初始项目文件作为基础，并同步存入全局 Map（按 threadId），
     * 解决条件边跨线程读取问题。
     */
    private String resolveRepairRoot(String threadId) throws IOException {
        return resolveRepairRootStatic(threadId);
    }

    /**
     * 静态获取（或创建）修复目录，供降级分支（条件边、无 Agent 实例）直接调用。
     * 首次创建时复制初始项目文件作为基础，并同步存入全局 Map（按 threadId）。
     */
    static String resolveRepairRootStatic(String threadId) throws IOException {
        String root = SandboxContext.getRepairProjectRoot();
        if (root == null && threadId != null) {
            root = SandboxContext.getRepairProjectRootForThread(threadId);
        }
        if (root == null) {
            // 服务重启后内存 Map 丢失，先从磁盘找回该 threadId 的修复目录，
            // 避免已修复的内容丢失（重建空目录会导致已修复文件全部丢失）。
            if (threadId != null) {
                root = CodeProjectWriter.findRepairRootForThread(threadId);
                if (root != null) {
                    SandboxContext.setRepairProjectRoot(root);
                    log.info("重启后找回已有修复目录: {} (threadId={})", root, threadId);
                }
            }
        }
        if (root == null) {
            Path baseDir = CodeProjectWriter.getStorageDir();
            Files.createDirectories(baseDir);
            // 目录名带流程 ID，便于调试追溯（codefix-{threadId}-{random}）
            String dirPrefix = (threadId != null && !threadId.isBlank())
                    ? "codefix-" + threadId + "-"
                    : "codefix-";
            root = Files.createTempDirectory(baseDir, dirPrefix).toRealPath().toString();
            SandboxContext.setRepairProjectRoot(root);
            log.info("修复临时目录已创建: {} (threadId={})", root, threadId);

            // 首次创建修复目录时，复制初始项目的完整文件作为基础，
            // 这样修复 Agent 只需写入修改的文件，其他文件保持不变。
            // 否则修复目录只有被修改的几个文件，缺少其他源文件导致编译失败。
            if (threadId != null) {
                String initialRoot = SandboxContext.getInitialProjectRoot(threadId);
                // 重启后内存 Map 丢失，初始目录也从磁盘找回
                if (initialRoot == null || initialRoot.isBlank()) {
                    initialRoot = CodeProjectWriter.findInitialRootForThread(threadId);
                }
                if (initialRoot != null && !initialRoot.isBlank()) {
                    copyDirectory(Path.of(initialRoot), Path.of(root));
                    log.info("初始项目文件已复制到修复目录: {} → {}",
                            initialRoot, root);
                }
            }
        }

        // 同时存入全局 Map（按 threadId），解决条件边跨线程读取问题
        if (threadId != null) {
            SandboxContext.setRepairProjectRootForThread(threadId, root);
        }
        return root;
    }

    /**
     * 从修复 Agent 的文本输出中提取代码文件并写入修复目录（降级前兜底）。
     * 模型未调用工具但输出了完整代码（``` 或 // FILE: 标记）时，直接落盘。
     *
     * 三层校验，任一不满足视为无有效提取：
     * 1. 路径来源：只认带真实文件路径的格式（// FILE: / #### 文件：），
     *    不用 parseMarkdownBlocks（其 GeneratedN 兜底命名无真实路径，不适合修复）。
     * 2. 内容形态：按文件类型校验结构特征（Java 需 package/class；XML 需标签；properties/yml 需键值）。
     * 3. 伪调用形态：内容以 writeCodeFile(/patchCodeFile(/readCodeFile( 指令开头 → 模型伪调用，丢弃。
     *
     * @return 写入的文件数；0 表示无可提取的完整文件
     */
    public static int writeFilesFromText(String threadId, String repairOutput) {
        if (repairOutput == null || repairOutput.isBlank()) return 0;
        try {
            Map<String, String> files = CodeProjectWriter.parse(repairOutput);
            if (files.isEmpty()) {
                files = CodeProjectWriter.parseMarkdownWithHeadings(repairOutput);
            }
            if (files.isEmpty()) return 0;

            String root = resolveRepairRootStatic(threadId);
            Path rootPath = Path.of(root);
            int written = 0;
            for (Map.Entry<String, String> entry : files.entrySet()) {
                String relative = entry.getKey().replaceFirst("^/workspace/", "").trim();
                if (relative.isBlank()) continue;
                Path target = rootPath.resolve(relative).normalize();
                if (!target.startsWith(rootPath.toAbsolutePath().normalize())) {
                    log.warn("文本提取跳过危险路径: {}", relative);
                    continue;
                }
                String content = CodeProjectWriter.stripCodeBlockMarkers(entry.getValue());
                if (content.isBlank()) continue;
                // 三层校验
                if (!isValidExtractedContent(relative, content)) {
                    log.info("文本提取内容校验不通过，丢弃: {} ({})", relative, threadId);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.writeString(target, content);
                log.info("文本提取写盘: {} ({})", target, threadId);
                written++;
            }
            if (written > 0) {
                SandboxContext.setRepairProjectRootForThread(threadId, root);
            }
            return written;
        } catch (Exception e) {
            log.warn("文本提取写盘失败: threadId={}, err={}", threadId, e.getMessage());
            return 0;
        }
    }

    /**
     * 文本提取内容三层校验。
     * 3. 伪调用形态：内容以工具调用指令开头（writeCodeFile(/patchCodeFile(/readCodeFile(）
     * 2. 内容形态：按文件扩展名校验结构特征
     */
    private static boolean isValidExtractedContent(String relative, String content) {
        String trimmed = content.trim();
        if (trimmed.isEmpty()) return false;
        // 3. 伪调用形态：模型把 writeCodeFile(...) 当文本输出（指令开头 + filePath= 参数）
        String lower = trimmed.toLowerCase();
        if ((lower.startsWith("writecodefile(") || lower.startsWith("patchcodefile(")
                || lower.startsWith("readcodefile(")) && trimmed.contains("filepath=")) {
            log.debug("文本提取识别为伪工具调用，丢弃: {}", relative);
            return false;
        }
        // 2. 内容形态：按类型校验结构特征
        String rl = relative.toLowerCase();
        if (rl.endsWith(".java")) {
            return trimmed.contains("package ") || trimmed.contains("class ")
                    || trimmed.contains("interface ") || trimmed.contains("enum ")
                    || trimmed.contains("record ") || trimmed.contains("import ");
        }
        if (rl.endsWith(".xml") || rl.endsWith(".pom")) {
            return trimmed.contains("<");
        }
        if (rl.endsWith(".properties")) {
            return trimmed.contains("=");
        }
        if (rl.endsWith(".yml") || rl.endsWith(".yaml")) {
            return trimmed.contains(":") || trimmed.contains("- ");
        }
        // 其他（无扩展名等）→ 保守校验：长度足够且不是纯指令文本
        return trimmed.length() > 10 && !lower.startsWith("writecodefile(")
                && !lower.startsWith("patchcodefile(") && !lower.startsWith("readcodefile(");
    }

    /**
     * 从 SseStreamingInterceptor 的全局注册表获取当前工作流的 threadId。
     * codeRepairAgent 的 outputKey 是 "generated_code"。
     */
    private String resolveCurrentThreadId() {
        return SseStreamingInterceptor.getActiveThreadId("generated_code");
    }

    /**
     * 递归复制目录（用于修复 Agent 首次创建修复目录时复制初始项目文件）。
     */
    private static void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source)) return;
        try (var stream = Files.walk(source)) {
            stream.forEach(sourcePath -> {
                try {
                    Path relative = source.relativize(sourcePath);
                    Path targetPath = target.resolve(relative);
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.createDirectories(targetPath.getParent());
                        Files.copy(sourcePath, targetPath,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.warn("复制文件失败: {} → {}", sourcePath, target, e);
                }
            });
        }
    }
}
