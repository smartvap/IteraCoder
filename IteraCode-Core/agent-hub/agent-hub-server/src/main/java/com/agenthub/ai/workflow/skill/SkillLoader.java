package com.agenthub.ai.workflow.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skill 加载器：从 classpath:skills/{category}/*.md 加载技能文件，
 * 以子目录名作为分类（dev/ops），解析 frontmatter 元数据和指令正文，供 ReactAgent 使用。
 * <p>
 * 自动跳过 skills-catalog.md（根目录下的非技能文件）。
 */
@Slf4j
@Component
public class SkillLoader {

    private static final String SKILLS_PATH = "classpath:skills/**/*.md";
    private static final String FRONTMATTER_START = "---";
    private static final String FRONTMATTER_END = "---";

    private Map<String, SkillContent> skills;

    public SkillLoader() {
        this.skills = new LinkedHashMap<>();
        loadSkills();
    }

    /**
     * 加载所有 skill 文件
     */
    private void loadSkills() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(SKILLS_PATH);
            for (Resource resource : resources) {
                try {
                    SkillContent content = parseSkillFile(resource);
                    // 跳过 skills 根目录下的非技能文件（如 skills-catalog.md）
                    if (content.getMetadata().getDirectory().isBlank()) {
                        log.debug("Skipped non-skill file: {}", resource.getFilename());
                        continue;
                    }
                    skills.put(content.getMetadata().getName(), content);
                    log.info("Loaded skill: {} [{}] ({})",
                            content.getMetadata().getName(),
                            content.getMetadata().getDirectory(),
                            content.getMetadata().getDisplayName());
                } catch (Exception e) {
                    log.warn("Failed to load skill file: {}", resource.getFilename(), e);
                }
            }
            log.info("SkillLoader initialized: {} skills loaded from {}", skills.size(), SKILLS_PATH);
        } catch (Exception e) {
            log.error("Failed to scan skill files from {}", SKILLS_PATH, e);
        }
    }

    /**
     * 解析单个 skill 文件，从路径提取父目录名作为 category
     */
    private SkillContent parseSkillFile(Resource resource) throws Exception {
        // 从路径提取目录名 e.g. "skills/dev/xxx.md" → "dev"
        String path = resource.getURI().toString();
        String directory = extractDirectory(path);

        StringBuilder rawContent = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawContent.append(line).append("\n");
            }
        }

        String fullContent = rawContent.toString();
        SkillMetadata metadata = parseFrontmatter(fullContent);
        // 以文件系统目录为准，frontmatter 中的 category 仅作参考
        metadata.setDirectory(directory);
        if (metadata.getCategory() == null || metadata.getCategory().isBlank()) {
            metadata.setCategory(directory);
        }

        String instruction = extractInstruction(fullContent);
        return new SkillContent(metadata, instruction);
    }

    /**
     * 从资源路径提取父目录名
     * e.g. ".../skills/dev/code-generation.md" → "dev"
     */
    private String extractDirectory(String path) {
        int skillsIdx = path.lastIndexOf("/skills/");
        if (skillsIdx < 0) {
            return "";
        }
        int dirStart = skillsIdx + "/skills/".length();
        int dirEnd = path.indexOf('/', dirStart);
        if (dirEnd < 0) {
            // 文件在 skills/ 根目录下，无子目录
            return "";
        }
        return path.substring(dirStart, dirEnd);
    }

    /**
     * 解析 YAML frontmatter
     */
    private SkillMetadata parseFrontmatter(String content) {
        SkillMetadata meta = new SkillMetadata();

        int startIdx = content.indexOf(FRONTMATTER_START);
        int endIdx = content.indexOf(FRONTMATTER_END, startIdx + FRONTMATTER_START.length());

        if (startIdx == 0 && endIdx > startIdx) {
            String frontmatter = content.substring(startIdx + FRONTMATTER_START.length(), endIdx);
            for (String line : frontmatter.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || !line.contains(":")) {
                    continue;
                }
                int colonIdx = line.indexOf(":");
                String key = line.substring(0, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();

                switch (key) {
                    case "name" -> meta.setName(value);
                    case "displayName" -> meta.setDisplayName(value);
                    case "description" -> meta.setDescription(value);
                    case "model" -> meta.setModel(value);
                    case "temperature" -> meta.setTemperature(Double.parseDouble(value));
                    case "outputKey" -> meta.setOutputKey(value);
                    case "category" -> meta.setCategory(value);
                    case "order" -> meta.setOrder(Integer.parseInt(value));
                    case "maxIterations" -> meta.setMaxIterations(Integer.parseInt(value));
                    case "maxRounds" -> meta.setMaxRounds(Integer.parseInt(value));
                    case "tools" -> meta.setTools(value);
                }
            }
        }
        return meta;
    }

    /**
     * 提取 frontmatter 之后的指令正文
     */
    private String extractInstruction(String content) {
        int endIdx = content.indexOf(FRONTMATTER_END);
        if (endIdx < 0) {
            return content;
        }
        int secondStart = content.indexOf(FRONTMATTER_START, endIdx + FRONTMATTER_END.length());
        if (secondStart >= 0) {
            return content.substring(secondStart + FRONTMATTER_START.length()).trim();
        }
        return content.substring(endIdx + FRONTMATTER_END.length()).trim();
    }

    /**
     * 获取指定技能的完整指令文本（已转义 ST4 的 {} 分隔符）。
     * <p>
     * Spring AI StTemplateRenderer 使用 { } 作为 ST4 分隔符，
     * skill 文件中 JSON/Java 代码示例含大量 {}，会触发 ST4 编译错误。
     * 此处保留合法的 {placeholder} 变量，转义其余 {}。
     */
    public String getInstruction(String skillName) {
        SkillContent content = skills.get(skillName);
        if (content == null) {
            log.warn("Skill not found: {}", skillName);
            return "";
        }
        return TASK_ANCHOR + LANG_DIRECTIVE + "\n\n" + escapeSt4Delimiters(content.getInstruction());
    }

    /**
     * 任务锚点：前置到每个 skill 指令最开头，防止模型把指令文档本身当成分析对象。
     * 关键：告诉模型指令中的方括号 [ ] 是格式占位符，不是示例答案；严禁输出模板示例内容。
     */
    private static final String TASK_ANCHOR =
            "【执行指令】以下是需要你执行的步骤框架。不要分析或评论本文档本身。\n"
            + "文档中用 [方括号] 标注的内容是输出格式占位符，你需要用实际分析结果替换它们，\n"
            + "而不是照抄方括号内的文字作为答案。禁止编造不在输入数据中的需求。\n";

    /**
     * 前置到每个 skill 指令开头，确保小模型（gemma2:2b 等）不会因首因效应忽略。
     * 指令必须极度简洁，小模型才能可靠遵循。
     */
    private static final String LANG_DIRECTIVE =
            "【重要指令】你必须使用中文回答。禁止输出英文。\n";

    // Spring AI StTemplateRenderer 使用 { } 作为 ST4 分隔符
    private static final Set<String> KNOWN_PLACEHOLDERS = Set.of(
            "requirement", "decomposition_result", "parallel_reasoning_result",
            "generated_code", "harness_result",
            "review_content", "review_decision",
            "review_feedback", "workflow_message", "workflow_status",
            "current_data", "question_answer_context",
            "max_repair_iterations", "repair_feedback"
    );

    // 匹配 {placeholder_name}，捕获其中的名称
    private static final Pattern PLACEHOLDER_PATTERN =
            Pattern.compile("\\{([a-z_]+)}");

    /**
     * 转义 ST4 模板分隔符。
     * Spring AI 的 StTemplateRenderer 使用 { } 作为 ST4 分隔符，
     * skill 指令中的 JSON 示例、Java 代码块含大量 {}，会触发 ST4 编译错误。
     * <p>
     * 处理策略：识别合法的 {placeholder} 变量先替换为哨兵token，
     * 将其余 { } 转义为 \{ \}，再还原哨兵token。
     */
    private String escapeSt4Delimiters(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        // Step 1: 将已知 {placeholder} 替换为哨兵标记
        StringBuffer sb = new StringBuffer();
        Matcher m = PLACEHOLDER_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (KNOWN_PLACEHOLDERS.contains(name)) {
                // 用唯一哨兵替换：__ST4_PH_<name>__
                m.appendReplacement(sb, "__ST4_PH_" + name + "__");
            }
        }
        m.appendTail(sb);
        String protectedText = sb.toString();

        // Step 2: 转义剩余的 { 和 }（使用 ST4 的 \{ 和 \} 转义）
        protectedText = protectedText.replace("{", "\\{");
        protectedText = protectedText.replace("}", "\\}");

        // Step 3: 将哨兵还原为 {placeholder}
        for (String name : KNOWN_PLACEHOLDERS) {
            protectedText = protectedText.replace("__ST4_PH_" + name + "__", "{" + name + "}");
        }

        return protectedText;
    }

    /**
     * 获取指定技能的元数据
     */
    public SkillMetadata getMetadata(String skillName) {
        SkillContent content = skills.get(skillName);
        return content != null ? content.getMetadata() : null;
    }

    /**
     * 获取指定技能的完整内容
     */
    public SkillContent getSkill(String skillName) {
        return skills.get(skillName);
    }

    /**
     * 获取所有已加载的技能
     */
    public Map<String, SkillContent> getAllSkills() {
        return new LinkedHashMap<>(skills);
    }

    /**
     * 按类别获取技能列表
     */
    public List<SkillContent> getSkillsByCategory(String category) {
        return skills.values().stream()
                .filter(s -> category.equals(s.getMetadata().getCategory()))
                .collect(Collectors.toList());
    }

    /**
     * 按排序获取技能列表
     */
    public List<SkillContent> getSkillsByOrder() {
        return skills.values().stream()
                .sorted((a, b) -> Integer.compare(
                        a.getMetadata().getOrder(),
                        b.getMetadata().getOrder()))
                .collect(Collectors.toList());
    }
}