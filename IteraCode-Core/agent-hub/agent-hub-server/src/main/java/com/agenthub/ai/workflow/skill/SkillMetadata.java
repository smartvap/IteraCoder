package com.agenthub.ai.workflow.skill;

import lombok.Data;

/**
 * Skill 元数据（解析 markdown frontmatter）
 */
@Data
public class SkillMetadata {
    private String name;
    private String displayName;
    private String description;
    private String model;
    private double temperature = 0.5;
    private String outputKey;
    private String directory;
    private String category;
    private int order;
    private int maxIterations;
    private int maxRounds;
    private String tools;
}