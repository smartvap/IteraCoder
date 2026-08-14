package com.agenthub.ai.workflow.tool;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 codeGenerationAgent 输出的结构化代码文本解析为真实项目文件。
 * <p>
 * 输入格式（由 code-generation.md skill 定义）：
 * <pre>
 *   // FILE: pom.xml
 *   &lt;project&gt;...&lt;/project&gt;
 *
 *   // FILE: src/main/java/com/example/Application.java
 *   package com.example;
 *   ...
 * </pre>
 * <p>
 * 使用方式：
 * <pre>
 *   CodeProjectWriter.Result result = CodeProjectWriter.write(generatedCode);
 *   String projectRoot = result.projectRoot(); // 传给 Sandbox 编译
 * </pre>
 */
@Slf4j
public final class CodeProjectWriter {

    /** 代码存储根目录，由 RdWorkflowGraphConfig 初始化 */
    static volatile String storagePath;

    /** 由 Spring 配置注入（调用方：RdWorkflowGraphConfig） */
    public static void setStoragePath(String path) {
        storagePath = (path != null && !path.isBlank())
                ? Path.of(path).normalize().toString()
                : Path.of(System.getProperty("user.dir"), "..", "temp").normalize().toString();
    }

    private static Path getStorageDir() {
        if (storagePath != null) return Path.of(storagePath);
        return Path.of(System.getProperty("user.dir"), "..", "temp").normalize();
    }

    private static final Pattern FILE_MARKER = Pattern.compile("^//\\s*FILE:\\s*(.+)$");

    /**
     * 清理 LLM 输出的文件路径中的噪声后缀。
     * 常见噪声：
     * - "(续)" / "（续）" — LLM 标注续接上一个文件
     * - "(1)" / "（2）" — LLM 编号
     * - 行尾注释 — 如 "pom.xml (完整版)" / "Application.java - 启动类"
     * - 前后引号 — LLM 有时给路径加引号
     */
    private static String sanitizeFilePath(String raw) {
        String path = raw.trim();
        // 去掉前后引号
        path = path.replaceAll("^[\"'`]+", "").replaceAll("[\"'`]+$", "");
        // 去掉行尾括号注释：xxx.java (续) / xxx.java（续）/ xxx.java (完整版)
        path = path.replaceAll("[\\(（][^\\)）]*[\\)）]\\s*$", "");
        // 去掉行尾 " - 注释" / " — 注释"
        path = path.replaceAll("\\s*[-—–]\\s*[^-—–]+$", "").trim();
        return path.trim();
    }

    private CodeProjectWriter() {
    }

    /**
     * 解析并写出项目文件到临时目录。
     *
     * @param generatedCode 代码生成 Agent 的完整输出
     * @return 项目根路径和各文件路径
     */
    public static Result write(String generatedCode) throws IOException {
        return write(generatedCode, null);
    }

    /**
     * 解析并写出项目文件到临时目录。
     *
     * @param generatedCode 代码生成 Agent 的完整输出
     * @param threadId 工作流流程 ID（用于目录命名，便于调试追溯；可为 null）
     * @return 项目根路径和各文件路径
     */
    public static Result write(String generatedCode, String threadId) throws IOException {
        if (generatedCode == null || generatedCode.isBlank()) {
            throw new IllegalArgumentException("generatedCode 为空，无法写出项目文件");
        }

        Map<String, String> files = parse(generatedCode);
        if (files.isEmpty()) {
            // 1. 尝试解析 markdown 代码块（```java ... ```）
            files = parseMarkdownBlocks(generatedCode);
        }
        if (files.isEmpty()) {
            // 2. 尝试解析带标题的 markdown 代码块（#### 文件：xxx + ``` 代码）
            files = parseMarkdownWithHeadings(generatedCode);
        }
        if (files.isEmpty()) {
            // 3. 最终兜底：将整段输出作为一个 Java 文件写出，让后续 Sandbox 自行处理
            log.warn("未找到 // FILE: 标记和 markdown 代码块，降级为原始输出落盘");
            files = Map.of("src/main/java/GeneratedCode.java", generatedCode);
        }

        Path projectRoot = createProjectTempDir("codegen-", threadId);
        List<String> writtenPaths = new ArrayList<>();

        for (Map.Entry<String, String> entry : files.entrySet()) {
            Path filePath = projectRoot.resolve(entry.getKey()).normalize();
            // 防止路径穿越
            if (!filePath.startsWith(projectRoot)) {
                log.warn("跳过危险路径: {}", entry.getKey());
                continue;
            }
            // 防御：模型输出的 FILE 标记可能是目录路径（无扩展名如 src/test/java），跳过
            String fileName = entry.getKey();
            if (!fileName.contains(".") || fileName.endsWith("/") || fileName.endsWith("\\")) {
                log.warn("跳过非文件路径: {}", fileName);
                continue;
            }
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, entry.getValue(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            writtenPaths.add(entry.getKey());
            log.debug("写出文件: {}", entry.getKey());
        }

        log.info("项目文件已写出: root={}, 文件数={}", projectRoot, writtenPaths.size());
        return new Result(projectRoot.toString(), writtenPaths);
    }

    /**
     * 解析代码文本，返回 文件路径 → 文件内容 映射（保持顺序）。
     */
    static Map<String, String> parse(String generatedCode) {
        Map<String, String> files = new LinkedHashMap<>();
        String[] lines = generatedCode.split("\n");

        String currentFile = null;
        StringBuilder currentContent = new StringBuilder();
        boolean foundFirstMarker = false;

        for (String line : lines) {
            Matcher m = FILE_MARKER.matcher(line.trim());
            if (m.matches()) {
                foundFirstMarker = true;
                // 保存上一个文件
                if (currentFile != null && !currentContent.isEmpty()) {
                    files.put(currentFile, stripTrailingNoise(stripCodeBlockMarkers(stripTrailingNewlines(currentContent))));
                }
                currentFile = sanitizeFilePath(m.group(1));
                currentContent = new StringBuilder();
            } else if (foundFirstMarker && currentFile != null) {
                currentContent.append(line).append("\n");
            }
        }
        // 保存最后一个文件
        if (currentFile != null && !currentContent.isEmpty()) {
            files.put(currentFile, stripTrailingNoise(stripCodeBlockMarkers(stripTrailingNewlines(currentContent))));
        }

        return files;
    }

    /**
     * 解析 markdown 代码块（```java ... ``` 或 ``` ... ```）。
     * 文件路径从代码块前的注释行推断，无法推断时按顺序编号。
     */
    static Map<String, String> parseMarkdownBlocks(String code) {
        Map<String, String> files = new LinkedHashMap<>();
        Pattern blockPattern = Pattern.compile("```(\\w*)\\s*\n(.*?)```", Pattern.DOTALL);
        Matcher m = blockPattern.matcher(code);
        int index = 0;
        String lastHint = null;
        while (m.find()) {
            String lang = m.group(1).toLowerCase();
            String content = stripTrailingNoise(stripCodeBlockMarkers(m.group(2))).strip();
            if (content.isEmpty()) continue;
            content = stripTrailingNoise(stripCodeBlockMarkers(content));

            String fileName;
            if (!lang.isEmpty()) {
                // 根据语言推断后缀
                fileName = "src/main/" + (lang.equals("java") ? "java/Generated" : "resources/Generated")
                        + (index > 0 ? index : "") + "." + (lang.equals("java") ? "java" : lang);
            } else {
                fileName = "src/main/java/Generated" + (index > 0 ? index : "") + ".java";
            }
            files.put(fileName, content);
            index++;
        }
        return files;
    }

    /**
     * 解析带标题的 markdown 代码块（#### 文件：xxx / #### File：xxx + 代码块）。
     * 覆盖 code-repair 等 Agent 常用但非 // FILE: 格式的输出。
     */
    static Map<String, String> parseMarkdownWithHeadings(String code) {
        Map<String, String> files = new LinkedHashMap<>();
        Pattern headingPattern = Pattern.compile(
                "(?i)^#{1,4}\\s*(?:文件|file)[:：\\s]+(.+?)$", Pattern.MULTILINE);
        Pattern codeBlockPattern = Pattern.compile(
                "^```(?:\\w*)\\s*$([\\s\\S]*?)^```\\s*$", Pattern.MULTILINE);

        Matcher headingMatcher = headingPattern.matcher(code);

        // 找到每个文件标题的位置
        record FileEntry(String path, int start) {}
        java.util.List<FileEntry> entries = new java.util.ArrayList<>();
        while (headingMatcher.find()) {
            String rawPath = headingMatcher.group(1).trim();
            // 去掉 /workspace/ 前缀 + 清理噪声后缀
            rawPath = rawPath.replaceFirst("^/workspace/", "").trim();
            rawPath = sanitizeFilePath(rawPath);
            if (!rawPath.isEmpty()) {
                entries.add(new FileEntry(rawPath, headingMatcher.end()));
            }
        }

        // 提取每个标题后面的代码块
        for (int i = 0; i < entries.size(); i++) {
            FileEntry entry = entries.get(i);
            int searchStart = entry.start;
            int searchEnd = (i + 1 < entries.size()) ? entries.get(i + 1).start : code.length();
            String section = code.substring(searchStart, searchEnd);

            Matcher codeMatcher = codeBlockPattern.matcher(section);
            if (codeMatcher.find()) {
                String content = stripTrailingNoise(stripCodeBlockMarkers(codeMatcher.group(1))).strip();
                if (!content.isEmpty()) {
                    files.put(entry.path, content);
                }
            }
        }
        return files;
    }

    private static String stripTrailingNewlines(StringBuilder sb) {
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /** 去除文件内容首尾的 markdown 代码块标记（``` 或 ```java 等，可多个） */
    public static String stripCodeBlockMarkers(String content) {
        if (content == null || content.isBlank()) return content;
        String[] lines = content.split("\n", -1);
        int start = 0, end = lines.length;
        // 去掉所有首行 ```xxx 标记
        while (start < end && lines[start].trim().startsWith("```")) start++;
        // 去掉所有尾行 ``` 标记
        while (end > start && lines[end - 1].trim().startsWith("```")) end--;
        if (start == 0 && end == lines.length) {
            // 即使没有完整的 ``` 行，也可能有散落的反引号残留在首尾行
            return stripStrayBackticks(content);
        }
        String result = String.join("\n", java.util.Arrays.copyOfRange(lines, start, end));
        return stripStrayBackticks(result);
    }

    /**
     * 清理首尾行中散落的单个反引号（LLM 偶尔在 XML/YAML 文件末尾留下 ``` 残片）。
     * 只清理首行开头和尾行结尾的反引号，不影响文件内容中的合法反引号。
     */
    private static String stripStrayBackticks(String content) {
        if (content == null || content.isEmpty()) return content;
        // 去掉首部纯反引号行或行首反引号
        String trimmed = content;
        // 去掉尾部散落的反引号（常见：XML 末尾多了个 `）
        trimmed = trimmed.replaceAll("`+\\s*$", "");
        return trimmed;
    }

    /**
     * 截断文件最后一个结构性元素之后的所有内容。
     * LLM 常在类/文件结尾 } 或 &lt;/tag&gt; 之后追加 Markdown 标题、中文说明等非代码内容，
     * 导致编译器报 illegal character 错误。
     *
     * <p>结构性元素判定：
     * <ul>
     *   <li>Java：行中包含 }（且不以 // 或 /* 开头，避免匹配注释中的 }）</li>
     *   <li>XML/pom.xml：行中包含 &lt;/ 且以 &gt; 结尾</li>
     * </ul>
     *
     * <p>从后往前找最后一个结构性元素行，截断该行之后的所有内容。
     */
    static String stripTrailingNoise(String content) {
        if (content == null || content.isBlank()) return content;

        String[] lines = content.split("\n", -1);
        int lastStructural = -1;

        // 从后往前搜索最后一个结构性元素行
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            // 跳过空行
            if (trimmed.isEmpty()) continue;

            // Java：包含 } 的行（排除注释行）
            if (trimmed.contains("}") && !trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*")) {
                lastStructural = i;
                break;
            }
            // XML/pom.xml：闭合标签 &lt;/xxx&gt;
            if (trimmed.contains("</") && trimmed.endsWith(">") && !trimmed.startsWith("<!--")) {
                lastStructural = i;
                break;
            }
            // 如果行明显是 Markdown/中文（# 开头、纯中文、含中文标点），继续往前找
            // 但不要在这一层就跳过——上面的规则已经覆盖了大部分情况
        }

        if (lastStructural >= 0 && lastStructural < lines.length - 1) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i <= lastStructural; i++) {
                sb.append(lines[i]);
                if (i < lastStructural) sb.append("\n");
            }
            String result = sb.toString().stripTrailing();
            log.debug("stripTrailingNoise: {} 行 → {} 行（截断尾部 {} 行噪声）",
                    lines.length, lastStructural + 1, lines.length - lastStructural - 1);
            return result;
        }

        return content;
    }

    /**
     * 在项目 temp/ 目录下创建子目录，避免 Windows 系统临时目录文件锁问题。
     *
     * @param prefix 目录前缀（如 "codegen-"）
     * @param threadId 工作流流程 ID，用于目录命名便于追溯（可为 null）
     */
    private static Path createProjectTempDir(String prefix, String threadId) throws IOException {
        Path baseDir = getStorageDir();
        Files.createDirectories(baseDir);
        String dirPrefix = (threadId != null && !threadId.isBlank())
                ? prefix + threadId + "-"
                : prefix;
        return Files.createTempDirectory(baseDir, dirPrefix).toRealPath();
    }

    /**
     * 按 threadId 扫描找回初始代码生成目录（codegen-{threadId}-*）。
     * 服务重启后 SandboxContext 内存 Map 丢失，但磁盘目录仍在，
     * 通过此方法找回，供 recover() 和 resolveRepairRoot 重建上下文。
     *
     * @param threadId 工作流流程 ID
     * @return 找到的目录绝对路径；未找到返回 null
     */
    public static String findInitialRootForThread(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        return findDirByPrefix("codegen-" + threadId + "-", threadId);
    }

    /**
     * 按 threadId 扫描找回修复目录（codefix-{threadId}-*）。
     * 服务重启后 SandboxContext 内存 Map 丢失，但磁盘目录仍在（已修复的内容不丢失），
     * 通过此方法找回修复目录，避免重建空目录导致已修复文件丢失。
     *
     * @param threadId 工作流流程 ID
     * @return 找到的目录绝对路径；未找到返回 null
     */
    public static String findRepairRootForThread(String threadId) {
        if (threadId == null || threadId.isBlank()) return null;
        return findDirByPrefix("codefix-" + threadId + "-", threadId);
    }

    /**
     * 在 temp/ 目录下按前缀查找第一个匹配目录。
     * 同一 threadId 理论上只有一个对应目录（幂等），若出现多个取最新的（modifiedTime 最大）。
     */
    private static String findDirByPrefix(String prefix, String threadId) {
        Path baseDir = getStorageDir();
        if (!Files.isDirectory(baseDir)) return null;
        try (var stream = Files.list(baseDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(prefix))
                    .max(java.util.Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .map(p -> {
                        try {
                            return p.toRealPath().toString();
                        } catch (IOException e) {
                            return p.toAbsolutePath().normalize().toString();
                        }
                    })
                    .orElse(null);
        } catch (IOException e) {
            log.warn("扫描临时目录失败: baseDir={}, prefix={}, threadId={}", baseDir, prefix, threadId, e);
            return null;
        }
    }

    /**
     * 删除指定工作流的所有临时项目目录（codegen-{threadId}-* / codefix-{threadId}-*）。
     * 工作流结束后调用，避免临时目录累积。
     *
     * @param threadId 工作流流程 ID
     */
    public static void cleanupForThread(String threadId) {
        if (threadId == null || threadId.isBlank()) return;
        Path baseDir = getStorageDir();
        if (!Files.isDirectory(baseDir)) return;
        try (var stream = Files.list(baseDir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("codegen-" + threadId + "-")
                                || name.startsWith("codefix-" + threadId + "-");
                    })
                    .forEach(p -> {
                        try {
                            try (var walk = Files.walk(p)) {
                                walk.sorted(java.util.Comparator.reverseOrder())
                                        .forEach(f -> {
                                            try {
                                                Files.delete(f);
                                            } catch (IOException e) {
                                                log.warn("删除临时文件失败: {}", f, e);
                                            }
                                        });
                            }
                            log.info("已清理工作流临时目录: {} (threadId={})", p, threadId);
                        } catch (IOException e) {
                            log.warn("清理工作流临时目录失败: {} (threadId={})", p, threadId, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("扫描临时目录失败: baseDir={}, threadId={}", baseDir, threadId, e);
        }
    }

    /**
     * 写出结果。
     */
    public record Result(String projectRoot, List<String> writtenFiles) {
    }
}
